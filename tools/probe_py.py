"""Cross-check the Java tokenizer against HuggingFace's.

Writes probe_py.txt next to this file; run TokenizerProbe.java the same way and
diff the two. Both must agree id-for-id.

Usage (from the repository root):
    python tools/probe_py.py

Requires: pip install transformers
"""
import os

from transformers import AutoTokenizer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MODEL_DIR = os.path.join(ROOT, "app", "assets", "models", "bge-small-zh")
OUT = os.path.join(HERE, "probe_py.txt")

TESTS = [
    "你好世界",
    "对方：你这两天怎么回事，消息也不回",
    "我：在忙",
    "Hello, world! 测试一下 mix 中英 123",
    "I don't know... really?!",
    "  multiple   spaces\tand\nnewlines  ",
    "标点，。！？；：（）【】《》",
    "数字1234和English words混排",
    "emoji 😀 和符号 @#￥%……&*",
]


def main():
    tok = AutoTokenizer.from_pretrained(MODEL_DIR)
    print("tokenizer:", type(tok).__name__, "vocab:", tok.vocab_size)

    with open(OUT, "w", encoding="utf-8") as f:
        for text in TESTS:
            enc = tok(text, truncation=True, max_length=128)
            f.write(text + "\n")
            f.write(" ".join(str(i) for i in enc["input_ids"]) + "\n")

    print("wrote", OUT)


if __name__ == "__main__":
    main()
