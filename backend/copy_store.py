"""Har bir foydalanuvchi uchun "manba xabar -> backup nusxa" moslashtirish
jadvalini (xaritani) serverda saqlaydi.

Nega kerak: forwarder.py yangi xabarni (endi MEDIALI HAM, MATNLI HAM)
"media bazasi" chatiga forward qilganda, asl xabar keyinchalik (asl
chatdan) o'chirilsa ham, klient bu xaritadan foydalanib backup nusxasini
topib, foydalanuvchiga ko'rsata oladi - HATTO boshqa qurilmadan login
qilingan bo'lsa ham, chunki xarita klientning lokal faylida emas, shu
yerda, serverda saqlanadi.

Format (copies.json): {
  "<user_key>": {
      "<source_chat_id>:<source_msg_id>": {
          "target_chat_id": <int>,
          "copy_msg_id": <int>,
          "ts": <unix_time>
      },
      ...
  },
  ...
}

Render'ning bepul tarifida disk doimiy emas (sessions.json uchun
README'dagi eslatmaga qarang) - bu fayl ham xuddi shunday vaqti-vaqti
bilan o'chib ketishi mumkin. Bu hozircha ma'lum cheklov sifatida qabul
qilingan (sessiyalar bilan bir xil holat); productionga chiqishda
ikkalasini ham tashqi/persistent saqlashga ko'chirish kerak bo'ladi.
"""

import json
import os
import threading
import time
from typing import Optional, Tuple

import config

_lock = threading.Lock()


def _load_raw() -> dict:
    if not os.path.exists(config.COPIES_FILE):
        return {}
    try:
        with open(config.COPIES_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError):
        return {}


def _save_raw(data: dict) -> None:
    tmp_path = config.COPIES_FILE + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(data, f)
    os.replace(tmp_path, config.COPIES_FILE)


def _key(chat_id: int, msg_id: int) -> str:
    return f"{chat_id}:{msg_id}"


def record_copy(
    user_key: str,
    source_chat_id: int,
    source_msg_id: int,
    target_chat_id: int,
    copy_msg_id: int,
) -> None:
    with _lock:
        data = _load_raw()
        user_map = data.get(user_key, {})
        user_map[_key(source_chat_id, source_msg_id)] = {
            "target_chat_id": target_chat_id,
            "copy_msg_id": copy_msg_id,
            "ts": int(time.time()),
        }
        # Cheksiz o'sib ketmasligi uchun: eng eski yozuvlarni tashlab yuboramiz.
        if len(user_map) > config.MAX_COPIES_PER_USER:
            oldest_first = sorted(user_map.items(), key=lambda kv: kv[1].get("ts", 0))
            overflow = len(user_map) - config.MAX_COPIES_PER_USER
            for k, _ in oldest_first[:overflow]:
                user_map.pop(k, None)
        data[user_key] = user_map
        _save_raw(data)


def get_copy(
    user_key: str, source_chat_id: int, source_msg_id: int
) -> Optional[Tuple[int, int]]:
    with _lock:
        data = _load_raw()
        entry = data.get(user_key, {}).get(_key(source_chat_id, source_msg_id))
        if not entry:
            return None
        return entry["target_chat_id"], entry["copy_msg_id"]


def delete_user(user_key: str) -> None:
    with _lock:
        data = _load_raw()
        data.pop(user_key, None)
        _save_raw(data)
