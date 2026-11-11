"""Client (ParadoksGram ilovasi) allaqachon login qilgan - uning qo'lida
o'z MTProto sessiyasi (dc_id + auth_key) bor. Bu modul o'sha mavjud
ma'lumotdan, qayta login qilmasdan, Telethon tushunadigan session
satrini yasaydi.
"""

import base64

from telethon.crypto import AuthKey
from telethon.sessions import StringSession

# Zaxira uchun: Telegram'ning ommaviy production DC manzillari. Client o'zi
# ulangan server_address/port'ni yuborsa, o'shanisi ishlatiladi - bu eng
# ishonchli variant, chunki client aynan shu manzilga ulangan holda ishlayapti.
_DEFAULT_DC_ADDRESSES = {
    1: ("149.154.175.53", 443),
    2: ("149.154.167.51", 443),
    3: ("149.154.175.100", 443),
    4: ("149.154.167.91", 443),
    5: ("91.108.56.130", 443),
}


def build_session_string(
    dc_id: int,
    auth_key_b64: str,
    server_address: str = None,
    port: int = None,
) -> str:
    if server_address is None or port is None:
        if dc_id not in _DEFAULT_DC_ADDRESSES:
            raise ValueError(f"DC {dc_id} uchun manzil berilmagan va standart ro'yxatda yo'q")
        server_address, port = _DEFAULT_DC_ADDRESSES[dc_id]

    auth_key_bytes = base64.b64decode(auth_key_b64)
    if len(auth_key_bytes) != 256:
        raise ValueError("auth_key 256 bayt bo'lishi kerak (MTProto 2.0 standart uzunligi)")

    session = StringSession()
    session.set_dc(dc_id, server_address, port)
    session.auth_key = AuthKey(data=auth_key_bytes)
    return session.save()
