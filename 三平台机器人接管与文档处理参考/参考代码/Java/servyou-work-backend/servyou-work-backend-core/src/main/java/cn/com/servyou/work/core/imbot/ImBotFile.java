package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.utils.OkHttpClientUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.FileTypeUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.crypto.Mode;
import cn.hutool.crypto.Padding;
import cn.hutool.crypto.symmetric.AES;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 平台文档暂存适配；复用会话上传接口，以磁盘流处理大文件并及时清理。 */
@lombok.extern.slf4j.Slf4j
public final class ImBotFile implements MultipartFile, AutoCloseable {
    /** 两平台消息附件接收上限，另预留企微加密填充空间。 */
    static final long INPUT_LIMIT=100L*1024*1024;
    /** 随机临时路径，不使用用户提供的文件名构造本地路径。 */
    private final Path path;
    /** 展示及会话上传使用的安全文件名称。 */
    private final String name;
    /** 文件内容类型。 */
    private final String mime;
    /** 实际解密后的文档长度。 */
    private final long size;

    private ImBotFile(Path path,String name,String mime,long size){this.path=path;this.name=name;this.mime=mime;this.size=size;}

    /** 限流复制至随机临时文件，失败时立即删除；不关闭调用方输入流。 */
    static ImBotFile copyOf(InputStream input,String name,String mime,long limit) throws IOException {
        Path path=Files.createTempFile("im-bot-document-",".tmp");
        try {
            long size;
            try(OutputStream out=Files.newOutputStream(path)){size=IoUtil.copy(input,out,IoUtil.DEFAULT_BUFFER_SIZE,limit+1,null);}
            if(size>limit)throw new BizException(Error.FILE_TOO_LARGE.getCode(),"文档超过平台文件大小限制");
            if(size==0)throw new BizException(Error.FILE_FAILED.getCode(),"文档内容为空");
            return new ImBotFile(path,mediaName(path,name),mime,size);
        } catch(IOException | RuntimeException e){Files.deleteIfExists(path);throw e;}
    }

    /** 下载平台提供的二进制流；下载地址由各平台适配器限定，不跟随重定向。 */
    static ImBotFile download(String url,Map<String,String> headers,String name,String aesKey) throws Exception {
        try(Response response=OkHttpClientUtil.getFileNoRetry(url,headers)) {
            if(!response.isSuccessful() || response.body()==null) {
                log.warn("平台附件下载被拒绝 httpStatus={}, contentType={}",response.code(),response.header("Content-Type"));
                throw new BizException(Error.FILE_FAILED.getCode(),"平台文档下载失败，HTTP "+response.code());
            }
            if(StringUtils.containsIgnoreCase(response.header("Content-Type"),"application/json")) {
                log.warn("平台附件下载返回JSON而非文件 httpStatus={}",response.code());
                throw new BizException(Error.FILE_FAILED.getCode(),"平台附件接口未返回文件，请核对当前身份的资源权限与授权有效期");
            }
            long limit=INPUT_LIMIT+(aesKey==null?0:32);
            if(response.body().contentLength()>limit)throw new BizException(Error.FILE_TOO_LARGE.getCode(),"输入文档不能超过100MB");
            if(StringUtils.isBlank(name) && response.header("Content-Disposition")!=null)
                name=ContentDisposition.parse(response.header("Content-Disposition")).getFilename();
            ImBotFile downloaded=copyOf(response.body().byteStream(),name,response.header("Content-Type"),limit);
            if(aesKey==null)return downloaded;
            try(ImBotFile encrypted=downloaded){return decryptWecom(encrypted,aesKey);}
        }
    }

    /** 企微文件采用 AES-CBC 和 32 字节 PKCS#7 填充，不能使用 Java 的 16 字节填充替代。 */
    static ImBotFile decryptWecom(ImBotFile encrypted,String keyText)throws Exception {
        byte[] key=Base64.decode(keyText);
        if(key.length!=32 || encrypted.size%16!=0)throw new BizException(Error.FILE_FAILED.getCode(),"企微文件密钥或密文长度无效");
        Path path=Files.createTempFile("im-bot-decrypted-",".tmp");
        try {
            try(InputStream in=encrypted.getInputStream();OutputStream out=Files.newOutputStream(path)) {
                new AES(Mode.CBC,Padding.NoPadding,key,Arrays.copyOf(key,16)).decrypt(in,out,false);
            }
            long size=Files.size(path);
            try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"rw")) {
                if(size==0)throw new BizException(Error.FILE_FAILED.getCode(),"企微文件解密结果为空");
                file.seek(size-1);int padding=file.readUnsignedByte();
                if(padding<1 || padding>32 || padding>size)throw new BizException(Error.FILE_FAILED.getCode(),"企微文件填充无效");
                file.seek(size-padding);
                for(int i=0;i<padding;i++)if(file.readUnsignedByte()!=padding)throw new BizException(Error.FILE_FAILED.getCode(),"企微文件填充无效");
                file.setLength(size-padding);size-=padding;
            }
            if(size<=0 || size>INPUT_LIMIT)throw new BizException(Error.FILE_FAILED.getCode(),"企微文档长度无效");
            return new ImBotFile(path,mediaName(path,encrypted.name),encrypted.mime,size);
        } catch(Exception e){Files.deleteIfExists(path);throw e;}
    }

    /** 未提供名称的媒体按真实文件头补扩展名，复用现有视觉、音频格式路由。 */
    private static String mediaName(Path path,String name)throws IOException {
        String safe=safeName(name);
        if(StringUtils.isBlank(name) || StringUtils.isBlank(org.apache.commons.io.FilenameUtils.getExtension(safe)) || safe.toLowerCase(Locale.ROOT).endsWith(".bin")) {
            String type=FileTypeUtil.getType(path.toFile());
            if(StringUtils.isNotBlank(type))return "上传附件."+type;
        }
        return safe;
    }

    /** 保留文件扩展名，移除路径和控制字符，避免污染执行工作区文件路径。 */
    static String safeName(String name) {
        String value=StringUtils.defaultIfBlank(name,"上传文档.bin").replace('\\','/');
        value=value.substring(value.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","").trim();
        if(StringUtils.isBlank(value) || ".".equals(value) || "..".equals(value) || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>240)
            throw new BizException(Error.FILE_FAILED.getCode(),"文件名称无效或过长");
        return value;
    }

    /** 当前临时文件路径，仅供平台上传读取。 */
    Path path(){return path;}
    @Override public String getName(){return "file";}
    @Override public String getOriginalFilename(){return name;}
    @Override public String getContentType(){return mime;}
    @Override public boolean isEmpty(){return size==0;}
    @Override public long getSize(){return size;}
    @Override public byte[] getBytes()throws IOException{return Files.readAllBytes(path);}
    @Override public InputStream getInputStream()throws IOException{return Files.newInputStream(path);}
    @Override public void transferTo(File target)throws IOException{Files.copy(path,target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    @Override public void close()throws IOException{Files.deleteIfExists(path);}
}
