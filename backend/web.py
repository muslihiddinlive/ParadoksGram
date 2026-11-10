"""ParadoksGram ilovasi shu API orqali foydalanuvchining media-backup
toggle'ini boshqaradi."""

import logging

from aiohttp import web

import auth
import session_store
from manager import SessionManager

log = logging.getLogger("web")


def build_app(manager: SessionManager) -> web.Application:
    app = web.Application()
    app["manager"] = manager

    async def health(request: web.Request) -> web.Response:
        # UptimeRobot shu endpointga ping qiladi
        return web.Response(text="ok")

    async def start_login(request: web.Request) -> web.Response:
        data = await request.json()
        phone = data["phone"]
        phone_code_hash = await auth.send_code(phone)
        return web.json_response({"phone_code_hash": phone_code_hash})

    async def verify_login(request: web.Request) -> web.Response:
        data = await request.json()
        phone = data["phone"]
        code = data["code"]
        phone_code_hash = data["phone_code_hash"]
        password = data.get("password")
        target_chat_id = data.get("target_chat_id")

        session_string = await auth.verify_code(phone, code, phone_code_hash, password)
        session_store.save_session(phone, session_string, target_chat_id)
        await request.app["manager"].start_forwarder(phone, session_string, target_chat_id)
        return web.json_response({"status": "connected"})

    async def set_target(request: web.Request) -> web.Response:
        user_key = request.match_info["user_key"]
        data = await request.json()
        request.app["manager"].set_target(user_key, int(data["target_chat_id"]))
        return web.json_response({"status": "updated"})

    async def status(request: web.Request) -> web.Response:
        user_key = request.match_info["user_key"]
        connected = request.app["manager"].is_connected(user_key)
        return web.json_response({"connected": connected})

    async def disconnect(request: web.Request) -> web.Response:
        user_key = request.match_info["user_key"]
        await request.app["manager"].stop_forwarder(user_key)
        session_store.delete_session(user_key)
        return web.json_response({"status": "disconnected"})

    app.router.add_get("/", health)
    app.router.add_post("/sessions/start", start_login)
    app.router.add_post("/sessions/verify", verify_login)
    app.router.add_post("/sessions/{user_key}/target", set_target)
    app.router.add_get("/sessions/{user_key}/status", status)
    app.router.add_post("/sessions/{user_key}/disconnect", disconnect)
    return app
