"""python -m unittest relay/test_kinosync.py  (from the repository root, or run inside relay/)"""
import base64
import gzip
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ["KINOSYNC_PRIMARY"] = "1"
import kinosync  # noqa: E402

G = "a" * 32
DEV_A, DEV_B = "0000000a", "0000000b"


def rid(n):
    return f"{n:032x}"


def packed(rows):
    return base64.urlsafe_b64encode(gzip.compress(json.dumps(rows).encode())).decode().rstrip("=")


class SyncTest(unittest.TestCase):
    def setUp(self):
        self.sync = kinosync.Sync(os.path.join(tempfile.mkdtemp(), "s.db"))

    def push(self, since, rows, dev=DEV_A, group=G):
        status, answer = self.sync.handle("/sync/push", {"g": [group], "s": [str(since)], "dev": [dev], "d": [packed(rows)]}, "", "1.1.1.1")
        self.assertEqual(status, 200, answer)
        return answer

    def test_last_write_wins_and_refused_come_back(self):
        a = self.push(0, [[rid(1), 100, DEV_A, "AAAA"], [rid(2), 100, DEV_A, "BBBB"]])
        # Its own records are not echoed back, but the number covers them.
        self.assertEqual(a["e"], [])
        self.assertEqual(a["seq"], 2)
        # B changed record 1 later, record 2 earlier: the first is taken, the second refused with what is stored.
        b = self.push(a["seq"], [[rid(1), 200, DEV_B, "CCCC"], [rid(2), 50, DEV_B, "DDDD"]], dev=DEV_B)
        self.assertEqual(b["e"], [])
        self.assertEqual([(x[0], x[1], x[3]) for x in b["x"]], [(rid(2), 100, "BBBB")])
        # A sees only what changed since its number.
        again = self.push(a["seq"], [])
        self.assertEqual([(e[0], e[3]) for e in again["e"]], [(rid(1), "CCCC")])
        # Equal time: the larger device id wins; the same record sent twice is no change.
        self.push(0, [[rid(3), 300, DEV_B, "EEEE"]], dev=DEV_B)
        tie = self.push(0, [[rid(3), 300, DEV_A, "FFFF"]])
        self.assertEqual([x[3] for x in tie["x"]], ["EEEE"])
        same = self.push(0, [[rid(3), 300, DEV_B, "EEEE"]], dev=DEV_B)
        self.assertEqual(same["x"], [])

    def test_deletion_and_paging(self):
        blob = "Q" * 4000
        self.push(0, [[rid(n), 10 + n, DEV_A, blob] for n in range(30)])
        seen, since = [], 0
        while True:
            page = self.push(since, [], dev=DEV_B)
            if not seen:
                self.assertEqual(page["more"], 1)
            seen += [e[0] for e in page["e"]]
            since = page["seq"]
            if not page["more"]:
                break
        self.assertEqual(seen, [rid(n) for n in range(30)])
        self.push(since, [[rid(5), 999, DEV_A, "GONE"]])
        other = self.push(since, [], dev=DEV_B)
        self.assertEqual([(e[0], e[3]) for e in other["e"]], [(rid(5), "GONE")])

    def test_epoch_is_kept(self):
        self.assertEqual(self.push(0, [])["epoch"], self.push(0, [])["epoch"])

    def test_pair_once_and_limits(self):
        lookup, wrapped = "b" * 64, "W" * 60
        status, _ = self.sync.handle("/sync/pair", {"l": [lookup], "w": [wrapped]}, "", "1.1.1.1")
        self.assertEqual(status, 200)
        status, answer = self.sync.handle("/sync/claim", {"l": [lookup]}, "", "2.2.2.2")
        self.assertEqual(answer.get("w"), wrapped)
        status, answer = self.sync.handle("/sync/claim", {"l": [lookup]}, "", "2.2.2.2")
        self.assertNotIn("w", answer)
        for _ in range(kinosync.CLAIMS_PER_HOUR):
            self.sync.handle("/sync/claim", {"l": ["c" * 64]}, "", "3.3.3.3")
        status, _ = self.sync.handle("/sync/claim", {"l": ["c" * 64]}, "", "3.3.3.3")
        self.assertEqual(status, 429)

    def test_bad_input(self):
        for query in ({"g": ["x"], "s": ["0"], "dev": [DEV_A]}, {"g": [G], "s": ["0"], "dev": ["zz"]},
                      {"g": [G], "s": ["0"], "dev": [DEV_A], "d": ["not-gzip"]}):
            status, _ = self.sync.handle("/sync/push", query, "", "1.1.1.1")
            self.assertEqual(status, 400)
        status, _ = self.sync.handle("/sync/push", {"g": [G], "s": ["0"], "dev": [DEV_A], "d": [packed([["zz", 1, DEV_A, ""]])]}, "", "1.1.1.1")
        self.assertEqual(status, 400)

    def test_forget(self):
        self.push(0, [[rid(1), 1, DEV_A, "AA"]])
        self.assertEqual(self.sync.handle("/sync/forget", {"g": [G]}, "", "1.1.1.1")[1]["deleted"], 1)
        self.assertEqual(self.push(0, [])["e"], [])


if __name__ == "__main__":
    unittest.main()
