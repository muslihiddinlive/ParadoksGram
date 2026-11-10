import asyncio
import logging

from aiohttp import web

import config
from manager import SessionManager
from web import build_app

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


async def main() -> None:
    manager = SessionManager()
    await manager.restore_all()

    app = build_app(manager)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", config.PORT)
    await site.start()
    logging.info("listening on port %s", config.PORT)

    await asyncio.Event().wait()  # abadiy ishlab turadi


if __name__ == "__main__":
    asyncio.run(main())
