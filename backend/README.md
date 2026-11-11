# ParadoksGram Media Backend

Foydalanuvchi sozlamalardan "media bazasi"ni ulaganda va forward
toggle'ini ikki marta tasdiqlaganda ishga tushadigan server: foydalanuvchi
nomidan (userbot sifatida) kiradigan Telegram sessiyasi orqali yangi media
xabarlarni avtomatik forward qiladi, qurilma o'chiq bo'lsa ham. Shu bilan
birga akkauntning "online"/"last seen" holatini yashirib turadi.

## Render.com'da deploy qilish

1. Render'da **New > Web Service** yarating, shu repo va
   `feature/media-backend` branch'ni tanlang.
2. **Root Directory**: `backend`
3. **Build Command**: `pip install -r requirements.txt`
4. **Start Command**: `python main.py`
5. Muhit o'zgaruvchilarini (Environment) qo'shing:
   - `TG_API_ID` va `TG_API_HASH` — https://my.telegram.org dan oling
   - `SESSION_ENCRYPT_KEY` — quyidagi buyruq bilan generatsiya qiling:
     ```
     python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
     ```
   - `PORT` ni qo'lda qo'yish shart emas, Render o'zi beradi.
6. Deploy tugagach, service URL'ingiz tayyor bo'ladi (masalan
   `https://sizning-app.onrender.com`).

## UptimeRobot ulash

UptimeRobot'da **HTTP(s)** turidagi monitor yarating:
- URL: `https://sizning-app.onrender.com/`
- Interval: 5 daqiqa

Bu Render'ning bo'sh (free) instansiyasini "sleep" holatiga ketishidan
saqlaydi.

> **Eslatma:** UptimeRobot faqat "sleep"ni to'xtatadi. Render free
> tarifida umumiy oylik ishlash soati chegarasi ham bo'lishi mumkin — buni
> Render dashboard'dan joriy holatiga qarab tekshiring, chunki shartlar
> vaqti-vaqti bilan o'zgaradi.

## API

Foydalanuvchi ilovada allaqachon login qilgan — backend qayta login,
SMS kod yoki boshqa tasdiqlash so'ramaydi. Ilova sozlamalardan "media
bazasi"ni yoqqanda, o'zining mavjud MTProto sessiyasidan kerakli
qiymatlarni (`dc_id`, `auth_key`) olib, bir marta shu endpoint'ga
jo'natadi:

- `GET /` — health check (UptimeRobot shu yerga ping qiladi)
- `POST /sessions/register`
  ```json
  {
    "user_key": "<telegram user_id>",
    "dc_id": 2,
    "auth_key": "<256 baytli auth_key, base64>",
    "server_address": "<ilova ulangan DC manzili, ixtiyoriy>",
    "port": 443,
    "target_chat_id": -100123456789
  }
  ```
  — mavjud sessiyadan Telethon session yasaydi, saqlaydi va forward'ni
  ishga tushiradi. `server_address`/`port` berilmasa, standart ommaviy
  DC manzillaridan foydalaniladi (`telethon_session.py`ga qarang) —
  lekin ilova o'zi ulangan manzilni yuborishi tavsiya etiladi, bu eng
  ishonchli variant.
- `POST /sessions/{user_key}/target` `{"target_chat_id": -100...}` —
  forward manzilini o'zgartiradi
- `GET /sessions/{user_key}/status` — ulanganmi yo'qmi
- `POST /sessions/{user_key}/disconnect` — forward'ni to'xtatadi va
  sessiyani o'chiradi (ilovadagi toggle o'chirilganda chaqiriladi)

### Android (client) tomonida nima kerak

Ilova `auth_key`ni (va `dc_id`ni) MTProto ulanishidan olib, base64'ga
o'girib, HTTPS orqali shu endpoint'ga yuborishi kerak. Bu qism
TMessagesProj'ning native/MTProto qatlamiga tegadi — xohlasangiz shu
qismni alohida ko'rib chiqamiz.

## Muhim cheklov: sessiyalar saqlanishi

Sessiya satrlari (`sessions.json`) shifrlangan holda local diskda
saqlanadi. Render'ning **free** Web Service'larida disk doimiy emas —
har bir redeploy yoki uzoq muddatli qayta ishga tushishda bu fayl
o'chib ketishi va foydalanuvchilar qayta ulanishi kerak bo'lishi mumkin.
Productionga chiqishdan oldin buni hal qilish variantlari:

- Render'ning pullik tarifidagi **Persistent Disk** qo'shish, yoki
- Sessiyalarni tashqi bazada (masalan sizning boshqa botlaringizdagi
  kabi, alohida Telegram kanalida shifrlangan xabar sifatida yoki
  haqiqiy DB'da) saqlash.

## Xavfsizlik

- `SESSION_ENCRYPT_KEY`ni hech qachon repo'ga commit qilmang — faqat
  Render Environment Variables orqali bering.
- `TG_API_ID`/`TG_API_HASH` ham shu tarzda maxfiy saqlanadi.
- `sessions.json` `.gitignore`'da — tasodifan commit qilinmaydi.
