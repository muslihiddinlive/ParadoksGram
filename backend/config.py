import os

# Telegram API credentials (my.telegram.org dan olinadi)
API_ID = int(os.environ["TG_API_ID"])
API_HASH = os.environ["TG_API_HASH"]

# Sessiya satrlarini shifrlash uchun kalit.
# Generatsiya qilish: python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
ENCRYPT_KEY = os.environ["SESSION_ENCRYPT_KEY"]

# Render "PORT" muhit o'zgaruvchisini o'zi beradi
PORT = int(os.environ.get("PORT", 8080))

# Sessiyalar saqlanadigan fayl (DIQQAT: Render free diskida restart/redeploy'da
# bu fayl o'chib ketadi - pastdagi README'dagi eslatmaga qarang)
SESSIONS_FILE = os.environ.get("SESSIONS_FILE", "sessions.json")

# Har necha soniyada "offline" statusni qayta tasdiqlab turish kerak
STATUS_REFRESH_SECONDS = int(os.environ.get("STATUS_REFRESH_SECONDS", "50"))
