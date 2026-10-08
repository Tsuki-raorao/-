import copy
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from relay_watch import accept_page, validate_result


class WatchStateTests(unittest.TestCase):
    def state(self):
        return {"cursor": 3, "history": [], "pending": []}

    def test_only_peer_messages_trigger_coordinator(self):
        state = self.state()
        messages = [dict(seq=4, sender="codex", text="own reply"),
                    dict(seq=5, sender="dot", text="proposal")]
        accept_page(state, dict(messages=messages, next_cursor=5))
        self.assertEqual([5], [m["seq"] for m in state["pending"]])
        self.assertEqual(5, state["cursor"])
        accept_page(state, dict(messages=[], next_cursor=5))
        self.assertEqual(1, len(state["pending"]))

    def test_replayed_page_is_rejected(self):
        with self.assertRaises(ValueError):
            accept_page(self.state(), dict(messages=[dict(seq=3, sender="dot")], next_cursor=3))

    def test_cursor_cannot_advance_without_messages(self):
        with self.assertRaises(ValueError):
            accept_page(self.state(), dict(messages=[], next_cursor=8))

    def test_result_cannot_echo_credential(self):
        result = dict(reply="test-only-credential", summary="", notification="", plan_agreed=False, needs_user=False)
        with self.assertRaises(ValueError):
            validate_result(result, "test-only-credential")

    def test_empty_reply_and_false_agreement_are_valid(self):
        result = dict(reply="", summary="waiting for proposal", notification="", plan_agreed=False, needs_user=False)
        self.assertEqual(result, validate_result(copy.deepcopy(result), "test-only-credential"))

    def test_string_false_cannot_trigger_notification(self):
        result = dict(reply="", summary="", notification="", plan_agreed="false", needs_user=False)
        with self.assertRaises(ValueError):
            validate_result(result, "test-only-credential")


if __name__ == "__main__":
    unittest.main()
