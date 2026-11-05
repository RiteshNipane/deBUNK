"""Normalizes fact-checkers' own wording ("Pants on Fire", "Partly false" ...) into a direction."""
from enum import Enum


class RatingClass(str, Enum):
    TRUE = "true"
    FALSE = "false"
    MISLEADING = "misleading"
    OTHER = "other"


# Order matters: "partly false" must hit MISLEADING before FALSE; "not true" must hit FALSE before TRUE.
_MISLEADING = ("misleading", "partly", "half", "mixture", "mixed", "missing context", "out of context",
               "exaggerat", "distort", "needs context", "cherry", "outdated", "old video", "old photo")
_FALSE = ("false", "fake", "pants on fire", "incorrect", "hoax", "fabricat", "no evidence", "not true",
          "untrue", "scam", "bogus", "wrong", "fraud", "doctored", "manipulated", "morphed", "satire")
_TRUE = ("true", "correct", "accurate", "real", "legit", "genuine", "verified", "confirmed")


def classify_rating(rating: str) -> RatingClass:
    r = rating.lower()
    if any(k in r for k in _MISLEADING):
        return RatingClass.MISLEADING
    if any(k in r for k in _FALSE):
        return RatingClass.FALSE
    if any(k in r for k in _TRUE):
        return RatingClass.TRUE
    return RatingClass.OTHER  # "unproven", "unclear": no direction
