"""ParadoksGram ilovasi shu API orqali foydalanuvchining media-backup
toggle'ini boshqaradi.

XAVFSIZ OQIM (QR-login, qr_login.py'ga qarang): foydalanuvchi "Media
bazasiga ulash"ni bosadi -> /sessions/qr/start backend uchun mustaqil
QR-login tokeni yaratadi (darhol qaytadi) -> ilova O'ZINING mavjud
sessiyasi orqali auth.acceptLoginToken bilan shu tokenni tasdiqlaydi
(Telegramning rasmiy "boshqa qurilmani ulash" mexanizmi) -> ilova
/sessions/qr/poll'ni tez-tez (1-2 soniyada bir) chaqirib, tasdiqlanganini
so'raydi - BU HECH QACHON uzoq bloklamaydi, shuning uchun mobil tarmoqda
ham timeout bo'lib qolmaydi. Ilovaning jonli auth_key'i hech qachon
backend'ga yuborilmaydi.

/sessions/register - ESKI, XAVFSIZ BO'LMAGAN usul (jonli auth_key'ni
to'g'ridan-to'g'ri nusxalash). Bu real foydalanishda Telegram'ning
seans-o'g'irlash tekshiruvini ishga tushirib, foydalanuvchi sessiyasini
bekor qilib qo'yganini aniqladik, shuning uchun doimiy o'chirilgan.
"""

import logging

from aiohttp import web

import config
import qr_login
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
        if config.MEDIA_BACKUP_DISABLED:
            log.warning("register_session refused: feature disabled (unsafe auth_key sharing)")
            return web.json_response(
                {
                    "status": "error",
                    "message": "Media bazasi funksiyasi xavfsizlik sababli vaqtincha o'chirilgan (akkount sessiyasi xavf ostida qolishi mumkin edi). Yangilanishni kuting.",
                },
                status=503,
            )
        try:
            data = await request.json()
            user_key = str(data["user_key"])  # masalan Telegram user_id
            dc_id = int(data["dc_id"])
            auth_key_b64 = data["auth_key"]  # base64, client o'zida allaqachon bor
            server_address = data.get("server_address")
            port = data.get("port")
            target_chat_id = data.get("target_chat_id")

            session_string = build_session_string(dc_id, auth_key_b64, server_address, port)
            await request.app["manager"].start_forwarder(user_key, session_string, target_chat_id)
            session_store.save_session(user_key, session_string, target_chat_id)
            return web.json_response({"status": "connected"})
        except Exception as e:
            log.exception("register_session failed")
            return web.json_response({"status": "error", "message": str(e)}, status=502)

    async def qr_start(request: web.Request) -> web.Response:
        try:
            data = await request.json()
            user_key = str(data["user_key"])
            target_chat_id = data.get("target_chat_id")
            result = await qr_login.start(user_key, target_chat_id)
            return web.json_response(result)
        except Exception as e:
            log.exception("qr_start failed")
            return web.json_response({"status": "error", "message": str(e)}, status=502)

    async def qr_poll(request: web.Request) -> web.Response:
        # Hech qachon uzoq bloklamaydi - darhol joriy holatni qaytaradi.
        try:
            data = await request.json()
            user_key = str(data["user_key"])
            result = await qr_login.poll(user_key, request.app["manager"])
            status_code = 200 if result.get("status") in ("waiting", "password_required", "connected") else 502
            return web.json_response(result, status=status_code)
        except Exception as e:
            log.exception("qr_poll failed")
            return web.json_response({"status": "error", "message": str(e)}, status=502)

    async def qr_password(request: web.Request) -> web.Response:
        try:
            data = await request.json()
            user_key = str(data["user_key"])
            password = data["password"]
            result = await qr_login.submit_password(user_key, password, request.app["manager"])
            status_code = 200 if result.get("status") == "ok" else 502
            return web.json_response(result, status=status_code)
        except Exception as e:
            log.exception("qr_password failed")
            return web.json_response({"status": "error", "message": str(e)}, status=502)

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
    app.router.add_post("/sessions/qr/start", qr_start)
    app.router.add_post("/sessions/qr/poll", qr_poll)
    app.router.add_post("/sessions/qr/password", qr_password)
    app.router.add_post("/sessions/{user_key}/target", set_target)
    app.router.add_get("/sessions/{user_key}/status", status)
    app.router.add_post("/sessions/{user_key}/disconnect", disconnect)
    return app
