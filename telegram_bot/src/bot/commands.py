"""Bot command menu + chat menu-button setup.

Registered once at startup (`__main__.setup_bot_commands`). The command list
drives Telegram's autocomplete (the «/» popover) and the blue menu button next
to the input field — без него пользователь не видит навигации по боту.

Descriptions are intentionally short: Telegram clips them in the popover and
caps each at 256 chars. Order here is the order shown to the user.
"""

from __future__ import annotations

from aiogram import Bot
from aiogram.types import (
    BotCommand,
    BotCommandScopeAllPrivateChats,
    MenuButtonCommands,
)

# Single source of truth for the «/» autocomplete menu. New commands added in
# later phases (detail-requests, etc.) are listed here so they surface in the
# popover even before the user discovers them in /help.
BOT_COMMANDS: list[BotCommand] = [
    BotCommand(command="start", description="Запуск и краткая подсказка"),
    BotCommand(command="help", description="Что умеет бот"),
    BotCommand(command="link", description="Привязать аккаунт: /link КОД"),
    BotCommand(command="dr", description="Заполнить запрос на детализацию"),
    BotCommand(command="dr_close", description="Закрыть текущий запрос детализации"),
    BotCommand(command="cancel", description="Отменить текущую операцию"),
    BotCommand(command="unlink", description="Как отвязать аккаунт"),
]


async def setup_bot_commands(bot: Bot) -> None:
    """Push the command list + enable the commands menu button.

    Scoped to private chats — the bot is single-user-facing; group scopes would
    just clutter. Idempotent: Telegram overwrites the previous set, so calling
    on every boot keeps the menu in sync with code.
    """
    await bot.set_my_commands(BOT_COMMANDS, scope=BotCommandScopeAllPrivateChats())
    await bot.set_chat_menu_button(menu_button=MenuButtonCommands())
