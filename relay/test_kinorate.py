"""python -m unittest relay/test_kinorate.py  (from the repository root, or run inside relay/)"""
import os
import sys
import tempfile
import unittest
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kinorate  # noqa: E402

FILM = "tt0133093"


def new_rates():
    return kinorate.Rates(os.path.join(tempfile.mkdtemp(), "r.db"))


class RatesTest(unittest.TestCase):
    def test_average_and_thumbs(self):
        r = new_rates()
        for stars, thumb in ((10, 1), (9, 1), (8, 1), (10, 1), (7, -1), (6, -1)):
            r.vote("1.1.1.1", FILM, uuid.uuid4().hex, stars=stars, thumb=thumb)
        self.assertEqual(r.aggregate([FILM])[FILM], [6, 4.2, 4, 2])

    def test_one_vote_per_token_and_clearing(self):
        r = new_rates()
        t = uuid.uuid4().hex
        r.vote("1.1.1.1", FILM, t, stars=8)
        r.vote("1.1.1.1", FILM, t, stars=6)
        self.assertEqual(r.aggregate([FILM])[FILM][:2], [1, 3.0])
        r.vote("1.1.1.1", FILM, t, stars=0)
        self.assertNotIn(FILM, r.aggregate([FILM]))

    def test_forget_zeroes_everything_of_a_token(self):
        r = new_rates()
        t = uuid.uuid4().hex
        r.vote("1.1.1.1", FILM, t, stars=8, thumb=1)
        r.vote("1.1.1.1", "kp301", t, stars=4)
        self.assertEqual(r.forget("1.1.1.1", t), 2)
        self.assertEqual(r.aggregate([FILM, "kp301"]), {})

    def test_bad_input_is_refused(self):
        r = new_rates()
        t = uuid.uuid4().hex
        for film, token, stars in (("x", t, 3), (FILM, "zz", 3), (FILM, t, 11)):
            with self.assertRaises(ValueError):
                r.vote("1.1.1.1", film, token, stars=stars)

    def test_two_servers_merge_by_last_write(self):
        a, b = new_rates(), new_rates()
        t = uuid.uuid4().hex
        a.vote("1.1.1.1", FILM, t, stars=10)
        b.merge(a.changes(0))
        self.assertEqual(b.aggregate([FILM]), a.aggregate([FILM]))
        b.vote("2.2.2.2", FILM, t, stars=2)
        a.merge(b.changes(0))
        self.assertEqual(a.aggregate([FILM])[FILM][:2], [1, 1.0])

    def test_a_script_cannot_bring_endless_new_tokens(self):
        r = new_rates()
        accepted = sum(r.vote("3.3.3.3", FILM, uuid.uuid4().hex, stars=8) is not None for _ in range(40))
        self.assertEqual(accepted, kinorate.IP_NEW_TOKENS_PER_DAY)


if __name__ == "__main__":
    unittest.main()
