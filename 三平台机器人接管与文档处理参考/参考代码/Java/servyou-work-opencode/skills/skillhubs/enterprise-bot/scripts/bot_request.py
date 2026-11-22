#!/usr/bin/env python3
"""使用当前工作台会话环境调用固定机器人 HTTP 接口，仅依赖 Python 标准库。"""

import argparse
import http.client
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


ACTIONS = {
    "takeover.detail": ("GET", "/takeover/detail", {"botKey"}),
    "tool.page": ("POST", "/tool/page", {"botKey", "capability", "tool", "keyword", "pageNum", "pageSize"}),
    "tool.execute": ("POST", "/tool/execute", {"botKey", "capability", "tool", "arguments", "sessionId", "conversationFileId"}),
    "document.capabilities": ("POST", "/document/capabilities", {"botKey"}),
    "document.execute": ("POST", "/document/execute", {"botKey", "action", "arguments", "sessionId", "conversationFileId"}),
    "bot.page": ("POST", "/page", {"botKey", "platform", "keyword", "pageNum", "pageSize"}),
    "binding.create_code": ("POST", "/binding/create-code", {"botKey"}),
    "binding.remove": ("POST", "/binding/remove", {"botKey"}),
    "session.page": ("POST", "/session/page", {"botKey", "sessionType", "keyword", "pageNum", "pageSize"}),
    "message.send": ("POST", "/message/send", {"botKey", "targetSessionKey", "requestId", "contentType", "content", "sourceSessionId", "conversationFileId"}),
    "message.page": ("POST", "/message/page", {"botKey", "platform", "direction", "processState", "sendState", "startTime", "endTime", "pageNum", "pageSize"}),
    "message.detail": ("GET", "/message/detail", {"messageId"}),
    "message.cancel": ("POST", "/message/cancel", {"messageId"}),
}
MAX_RESPONSE_BYTES = 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """认证请求不跟随跳转，避免把当前用户凭据发往其他地址。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate(action, payload):
    if not isinstance(payload, dict):
        raise ValueError("业务参数必须为 JSON 对象")
    if set(payload) - ACTIONS[action][2]:
        raise ValueError("存在此操作不支持的字段")
    required = []
    if action == "takeover.detail":
        required.append("botKey")
    if action in ("binding.create_code", "binding.remove", "session.page", "message.send", "document.capabilities", "document.execute", "tool.page", "tool.execute"):
        required.append("botKey")
    if action in ("message.detail", "message.cancel"):
        required.append("messageId")
    if action == "message.send":
        required.extend(("targetSessionKey", "requestId"))
        if payload.get("contentType") == "FILE":
            required.extend(("sourceSessionId", "conversationFileId"))
        else:
            required.append("content")
        payload.setdefault("contentType", "TEXT")
    for field in required:
        if field not in payload or payload[field] is None or payload[field] == "":
            raise ValueError(field + " 必填")
    for field in ("botKey", "targetSessionKey", "messageId", "conversationFileId"):
        value = payload.get(field)
        if value is not None and (not isinstance(value, str) or not value.isascii()
                                  or not value.isdigit() or len(value) > 19
                                  or value.startswith("0") or int(value) > 9223372036854775807):
            raise ValueError(field + " 必须为正整数格式的 Long 范围字符串")
    if action == "message.send":
        request_id = payload["requestId"]
        if (not isinstance(request_id, str) or not request_id.strip() or len(request_id) > 128
                or request_id != request_id.strip() or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in request_id)):
            raise ValueError("requestId 格式无效")
        content = payload.get("content", "")
        if payload["contentType"] != "FILE" and (not isinstance(content, str) or not content.strip() or len(content.encode("utf-8")) > 16000):
            raise ValueError("正文不能为空且不能超过16000个UTF-8字节")

    if action.startswith("tool."):
        if payload.get("capability") not in ("doc", "msg", "todo", "schedule", "meeting", "disk", "contact"):
            raise ValueError("必须指定七类企微能力之一")
        if action == "tool.execute":
            tool = payload.get("tool")
            if not isinstance(tool, str) or not tool or len(tool) > 128 or not all(c.isascii() and (c.isalnum() or c in "_-") for c in tool):
                raise ValueError("tool必须使用后端实际返回的工具名称")
            if not isinstance(payload.get("arguments", {}), dict):
                raise ValueError("工具arguments必须为对象")

    if action == "document.execute":
        if not isinstance(payload.get("action"), str) or not payload["action"].strip():
            raise ValueError("文档 action 必填")
        if not isinstance(payload.get("arguments", {}), dict):
            raise ValueError("文档 arguments 必须为对象")


def call_backend(action, payload):
    base = os.environ.get("BACKEND_API_BASE", "")
    token = os.environ.get("MH_TOKEN", "")
    if not base:
        return {"success": False, "code": "MISSING_BACKEND_API_BASE", "message": "环境变量 BACKEND_API_BASE 未设置"}
    if not token or any(ord(c) < 33 or ord(c) > 126 or c == ";" for c in token):
        return {"success": False, "code": "MISSING_MH_TOKEN", "message": "当前环境缺少有效 MH_TOKEN，请重新登录工作台"}
    parts = urllib.parse.urlsplit(base)
    if (parts.scheme not in ("http", "https") or not parts.hostname or parts.username
            or parts.password or parts.query or parts.fragment):
        return {"success": False, "code": "INVALID_BACKEND_API_BASE", "message": "环境变量 BACKEND_API_BASE 格式无效"}
    method, path, _ = ACTIONS[action]
    prefix = parts.path.rstrip("/") or "/servyou-work-backend"
    query = urllib.parse.urlencode(payload) if method == "GET" else ""
    url = urllib.parse.urlunsplit((parts.scheme, parts.netloc, prefix + "/im/bot" + path, query, ""))
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if method == "POST" else None
    headers = {"Accept": "application/json", "Content-Type": "application/json;charset=UTF-8",
               "Cookie": "ETAX_ACCESS_TOKEN_KEY=" + token}
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(request, timeout=180 if action in ("document.execute", "tool.execute", "tool.page") else 15) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as error:
        with error:
            if error.code in (401, 403):
                return {"success": False, "code": "HTTP_" + str(error.code),
                        "message": "后端认证或授权未通过，请检查当前登录及机器人使用权限"}
            if 300 <= error.code < 400:
                return {"success": False, "code": "REDIRECT_REJECTED",
                        "message": "后端返回跳转，已停止请求，请检查环境中的后端地址"}
            raw = error.read(MAX_RESPONSE_BYTES + 1)
    except (urllib.error.URLError, OSError, http.client.HTTPException):
        return {"success": False, "code": "REQUEST_UNCONFIRMED",
                "message": "HTTP请求未确认；工具写入不得自动重放，请按原资源ID或任务ID核查；消息发送保留原requestId查询，不换号重发",
                "requestId": payload.get("requestId")}
    if len(raw) > MAX_RESPONSE_BYTES:
        return {"success": False, "code": "RESPONSE_TOO_LARGE", "message": "接口响应过大，请缩小查询范围"}
    try:
        result = json.loads(raw)
    except (ValueError, UnicodeError):
        return {"success": False, "code": "INVALID_RESPONSE",
                "message": "后端未返回有效JSON；发送结果需使用原requestId核查"}
    if not isinstance(result, dict):
        return {"success": False, "code": "INVALID_RESPONSE", "message": "后端响应必须为JSON对象"}
    # 复用后端 CommonResult/CommonResultPage，保留分页元数据；不输出认证值。
    return json.loads(json.dumps(result, ensure_ascii=False).replace(token, "****"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=sorted(ACTIONS))
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--payload", help="仅含业务字段的JSON对象")
    source.add_argument("--payload-file", help="仅含业务字段的UTF-8 JSON文件")
    args = parser.parse_args()
    try:
        if args.payload_file:
            with open(args.payload_file, encoding="utf-8") as handle:
                payload = json.load(handle)
        else:
            payload = json.loads(args.payload or "{}")
        validate(args.action, payload)
        result = call_backend(args.action, payload)
    except (ValueError, OSError):
        # 不输出异常原文中的输入正文、文件路径或环境凭据。
        result = {"success": False, "code": "INVALID_REQUEST",
                  "message": "业务参数或当前运行环境无效，请检查必填字段、ID格式及环境变量是否已注入"}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("success") is True else 1


if __name__ == "__main__":
    sys.exit(main())
