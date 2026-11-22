package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums;
import cn.com.servyou.work.api.exception.BizException;
import cn.hutool.crypto.Mode;
import cn.hutool.crypto.Padding;
import cn.hutool.crypto.symmetric.AES;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 校验真实字节流及官方32字节填充协议，不用伪造平台收发结果替代联调。 */
class ImBotFileTest {
    @Test void wecomDecryptsAllPaddingLengthsAndReleasesTemporaryFiles()throws Exception {
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        for(int size=1;size<=64;size++) {
            byte[] plain=new byte[size];Arrays.fill(plain,(byte)('a'+size%20));int padding=32-size%32;
            byte[] padded=Arrays.copyOf(plain,size+padding);Arrays.fill(padded,size,padded.length,(byte)padding);
            byte[] encrypted=new AES(Mode.CBC,Padding.NoPadding,key,Arrays.copyOf(key,16)).encrypt(padded);
            Path encryptedPath,plainPath;
            try(ImBotFile source=ImBotFile.copyOf(new ByteArrayInputStream(encrypted),"文档.docx","application/octet-stream",1000);
                ImBotFile result=ImBotFile.decryptWecom(source,Base64.getEncoder().encodeToString(key))) {
                encryptedPath=source.path();plainPath=result.path();assertEquals("文档.docx",result.getOriginalFilename());
                assertArrayEquals(plain,result.getBytes());assertEquals(size,result.getSize());
            }
            assertFalse(Files.exists(encryptedPath));assertFalse(Files.exists(plainPath));
        }
    }
    @Test void boundedCopiesRejectEmptyAndOversizedDocuments()throws Exception {
        BizException large=assertThrows(BizException.class,()->ImBotFile.copyOf(new ByteArrayInputStream(new byte[33]),"a.txt","text/plain",32));
        assertEquals(ImBotEnums.Error.FILE_TOO_LARGE.getCode(),large.getCode());
        assertThrows(BizException.class,()->ImBotFile.copyOf(new ByteArrayInputStream(new byte[0]),"a.txt","text/plain",32));
        assertEquals("成果报告.docx",ImBotFile.safeName("../../成果报告.docx"));
        assertThrows(BizException.class,()->ImBotFile.safeName(".."));
        try(ImBotFile file=ImBotFile.copyOf(new ByteArrayInputStream("真实流测试".getBytes(StandardCharsets.UTF_8)),"a.txt","text/plain",32)) {
            assertEquals("真实流测试",new String(file.getBytes(),StandardCharsets.UTF_8));
        }
    }
    @Test void corruptWecomPaddingIsRejected()throws Exception {
        byte[] key=new byte[32],bad=new byte[32];bad[31]=33;
        byte[] bytes=new AES(Mode.CBC,Padding.NoPadding,key,new byte[16]).encrypt(bad);
        try(ImBotFile source=ImBotFile.copyOf(new ByteArrayInputStream(bytes),"a.pdf","application/pdf",100)) {
            assertThrows(BizException.class,()->ImBotFile.decryptWecom(source,Base64.getEncoder().encodeToString(key)));
        }
    }
}
