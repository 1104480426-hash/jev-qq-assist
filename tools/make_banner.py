"""用真实素材合成一张 GitHub 社交预览图 (1280x640)。

素材全部来自仓库自身的产物：
  docs/icon.png       应用图标（玻璃珠）
  docs/screenshot.png 实机截图（两态对比）

风格与 App 一致：深青底、青绿强调色、玻璃质感、克制留白。

用法:
    python tools/make_banner.py
"""
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DOCS = os.path.join(ROOT, "docs")

W, H = 1280, 640
ACCENT = (43, 196, 160)
ACCENT_SOFT = (127, 240, 212)
BG_TOP = (13, 46, 42)
BG_BOTTOM = (6, 16, 15)
TEXT = (236, 244, 242)
MUTED = (150, 176, 170)


def font(size, bold=True):
    candidates = [
        r"C:\Windows\Fonts\msyhbd.ttc" if bold else r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf",
        r"C:\Windows\Fonts\arialbd.ttf" if bold else r"C:\Windows\Fonts\arial.ttf",
    ]
    for path in candidates:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def backplate():
    """深青渐变底 + 两团光晕，托出玻璃的发光感。"""
    bg = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(bg)
    for y in range(H):
        t = (y / (H - 1)) ** 1.15
        c = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
        d.line([(0, y), (W, y)], fill=c)

    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([760, -220, 1420, 400], fill=(43, 196, 160, 46))
    gd.ellipse([-160, 330, 420, 800], fill=(43, 196, 160, 26))
    glow = glow.filter(ImageFilter.GaussianBlur(120))
    return Image.alpha_composite(bg.convert("RGBA"), glow)


def rounded_glass(size, radius, alpha=64):
    """一块玻璃面板：半透明白 + 顶部受光 + 折射边。"""
    w, h = size
    panel = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(panel)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=(255, 255, 255, alpha))
    sheen = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(sheen).rounded_rectangle(
        [0, 0, w - 1, int(h * 0.5)], radius=radius, fill=(255, 255, 255, 34))
    sheen = sheen.filter(ImageFilter.GaussianBlur(28))
    panel = Image.alpha_composite(panel, sheen)
    ImageDraw.Draw(panel).rounded_rectangle(
        [0, 0, w - 1, h - 1], radius=radius, outline=(255, 255, 255, 92), width=1)
    return panel


def main():
    canvas = backplate()

    # ---- 左侧文案 ----
    d = ImageDraw.Draw(canvas)

    icon = Image.open(os.path.join(DOCS, "icon.png")).convert("RGBA")
    icon = icon.resize((92, 92), Image.LANCZOS)
    canvas.alpha_composite(icon, (78, 92))

    d.text((190, 100), "Jev QQ Assist", font=font(48), fill=TEXT)
    d.text((192, 158), "基于 Jev 的 QQ 聊天决策辅助", font=font(22, bold=False), fill=ACCENT)

    body = [
        "读当前聊天窗口，给出类型化判定：",
        "是否在等回复 · 对方意图 · 情绪强度 · 说错话风险 · 推荐策略",
    ]
    d.text((80, 236), body[0], font=font(21, bold=False), fill=MUTED)
    d.text((80, 268), body[1], font=font(21, bold=False), fill=MUTED)

    a = "它只给判决，不生成回复文本，也从不代发消息。"
    d.text((80, 312), a, font=font(21, bold=False), fill=MUTED)

    # 三个卖点做成胶囊
    chips = ["本地推理 · 离线可用", "单次判定 49ms", "不代发消息"]
    x = 80
    for c in chips:
        f = font(17, bold=False)
        tw = d.textlength(c, font=f)
        cw, ch = int(tw) + 34, 38
        chip = rounded_glass((cw, ch), ch // 2, alpha=30)
        canvas.alpha_composite(chip, (x, 372))
        d.text((x + 17, 372 + 9), c, font=f, fill=ACCENT_SOFT)
        x += cw + 12

    d.text((80, 452), "github.com/1104480426-hash/jev-qq-assist",
           font=font(18, bold=False), fill=(96, 122, 117))

    # ---- 右侧：真实截图 ----
    shot = Image.open(os.path.join(DOCS, "screenshot.png")).convert("RGBA")
    # 源图是两态对比：顶部 56px 是标签、两侧各留 16px 边距，这里只取左边那块
    # 卡片态，并且把标签和边距都裁掉，避免把说明文字带进海报。
    left = 16
    top = 58
    half_w = (shot.width - 18 - 32) // 2
    shot = shot.crop((left, top, left + half_w, shot.height))

    target_h = 496
    scale = target_h / shot.height
    shot = shot.resize((int(shot.width * scale), target_h), Image.LANCZOS)

    frame = Image.new("RGBA", (shot.width + 20, shot.height + 20), (0, 0, 0, 0))
    fd = ImageDraw.Draw(frame)
    fd.rounded_rectangle([0, 0, frame.width - 1, frame.height - 1],
                         radius=26, fill=(255, 255, 255, 20),
                         outline=(255, 255, 255, 70), width=2)
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, shot.width - 1, shot.height - 1], radius=18, fill=255)
    frame.alpha_composite(Image.composite(shot, Image.new("RGBA", shot.size, (0, 0, 0, 0)), mask), (10, 10))

    # 给手机加一层投影，别让它像是贴上去的
    shadow = Image.new("RGBA", (frame.width + 120, frame.height + 120), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        [60, 60, 60 + frame.width, 60 + frame.height],
        radius=30, fill=(0, 0, 0, 150))
    shadow = shadow.filter(ImageFilter.GaussianBlur(38))

    px = W - frame.width - 74
    py = (H - frame.height) // 2
    canvas.alpha_composite(shadow, (px - 60, py - 60))
    canvas.alpha_composite(frame, (px, py))

    out = os.path.join(DOCS, "banner.png")
    canvas.convert("RGB").save(out, "PNG", optimize=True)
    print("banner:", canvas.size, os.path.getsize(out), "bytes")


if __name__ == "__main__":
    main()
