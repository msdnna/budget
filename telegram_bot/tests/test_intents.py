"""Unit tests for the pure intent-classification helpers (no LLM/network)."""

from __future__ import annotations

from bot.intents import (
    ALL_INTENTS,
    INTENT_RECURRING,
    INTENT_TRANSACTION,
    INTENT_WISHLIST,
    _classifier_prompt,
)


def test_all_intents_shape():
    # transaction is the fallback; the other four are trigger-bearing.
    assert INTENT_TRANSACTION in ALL_INTENTS
    assert set(ALL_INTENTS) == {
        "transaction",
        "wishlist",
        "recurring_payment",
        "link_existing",
        "detail_request",
    }


def test_classifier_prompt_embeds_db_triggers():
    triggers = {INTENT_WISHLIST: ["очень хочу", "куплю"], INTENT_RECURRING: ["за свет"]}
    prompt = _classifier_prompt(triggers)
    # Admin/DB phrases surface as hint lines.
    assert "очень хочу" in prompt
    assert "за свет" in prompt
    # Every intent name is described.
    for intent in ALL_INTENTS:
        assert intent in prompt


def test_classifier_prompt_without_triggers_still_lists_intents():
    prompt = _classifier_prompt({})
    # No hint block, but the prose intent descriptions are always present.
    assert INTENT_TRANSACTION in prompt
    assert "Слова/фразы-подсказки" not in prompt
