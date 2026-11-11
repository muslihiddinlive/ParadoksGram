"""ParadoksGram ilovasi shu API orqali foydalanuvchining media-backup
toggle'ini boshqaradi.

Oqim: foydalanuvchi ilovada allaqachon login qilgan -> sozlamalardan
"media bazasi"ni yoqadi -> ilova O'ZINING mavjud sessiyasidan
(dc_id + auth_key) /sessions/register'ga bir marta yuboradi. Qayta
login, SMS kod yoki boshqa narsa talab qilinmaydi.
"""

import logging

from aiohttp import web

import session_store
from manager import SessionManager
from telethon_session import build_session_string

log = logging.getLogger("web")


def build_app(manager: SessionManager) -> web.Application:
    app = web.Application()
    app["manager"] = manager

    async def health(request: web.Request) -> web.Response:
        # UptimeRobot shu endpointga ping qiladi
        return web.Response(text="ok")

    async def register_session(request: web.Request) -> web.Response:
        data = await request.json()
        user_key = str(data["user_key"])  # masalan Telegram user_id
        dc_id = int(data["dc_id"])
        auth_key_b64 = data["auth_key"]  # base64, client o'zida allaqachon bor
        server_address = data.get("server_address")
        port = data.get("port")
        target_chat_id = data.get("target_chat_id")

        session_string = build_session_string(dc_id, auth_key_b64, server_address, port)
        session_store.save_session(user_key, session_string, target_chat_id)
        await request.app["manager"].start_forwarder(user_key, session_string, target_chat_id)
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
        # Foydalanuvchi sozlamalardan toggle'ni o'chirganda shu chaqiriladi
        user_key = request.match_info["user_key"]
        await request.app["manager"].stop_forwarder(user_key)
        session_store.delete_session(user_key)
        return web.json_response({"status": "disconnected"})

    app.router.add_get("/", health)
    app.router.add_post("/sessions/register", register_session)
    app.router.add_post("/sessions/{user_key}/target", set_target)
    app.router.add_get("/sessions/{user_key}/status", status)
    app.router.add_post("/sessions/{user_key}/disconnect", disconnect)
    return app
