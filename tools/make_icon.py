"""生成 Jev 聊天参谋的应用图标。

液态玻璃质感。要点在三点：玻璃体本身要透亮而不是一块死青；边缘得有一圈受光的
折射亮线（rim light），这是"玻璃"和"塑料"的分界；顶部高光要锐利、底部要有回弹光。
小尺寸下会被缩到 48px，所以结构必须简单、对比必须够。

输出自适应图标需要的 foreground（各密度，含安全边距）、传统图标 fallback，
以及一张 512px 预览图。

用法:
    python tools/make_icon.py
"""
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "app", "res")
SOURCE_ICON = os.path.join(ROOT, "docs", "icon-final-source.png")

FOREGROUND_BASE = 432      # 108dp @ 4x
LEGACY_BASE = 192          # 48dp @ 4x

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

# 新版品牌色：深蓝底 + 青绿色双翼，缩小后仍保持轮廓和对比度。
BG_TOP = (7, 34, 82)
BG_BOTTOM = (5, 18, 48)
WING_TEAL = (36, 224, 194)
WING_MINT = (155, 246, 215)
MARK_WHITE = (255, 255, 255)


def find_font(size):
    for path in [
        r"C:\Windows\Fonts\segoeuib.ttf",
        r"C:\Windows\Fonts\arialbd.ttf",
        r"C:\Windows\Fonts\msyhbd.ttc",
    ]:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def ellipse_mask(size, box):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse(box, fill=255)
    return mask


def vertical_gradient(size, top, bottom, gamma=1.0):
    grad = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(grad)
    for y in range(size):
        t = (y / max(1, size - 1)) ** gamma
        color = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        draw.line([(0, y), (size, y)], fill=color + (255,))
    return grad


def radial_glow(size, center, radius, inner_alpha, blur=None):
    """从中心向外衰减的白色光斑。"""
    layer = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    alpha = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(alpha)
    steps = 110
    for i in range(steps, 0, -1):
        t = i / steps
        r = radius * t
        a = int(inner_alpha * (1.0 - t) ** 1.6)
        if r <= 0 or a <= 0:
            continue
        draw.ellipse([center[0] - r, center[1] - r, center[0] + r, center[1] + r], fill=a)
    layer.putalpha(alpha)
    if blur:
        layer = layer.filter(ImageFilter.GaussianBlur(blur))
    return layer


def soft_ellipse(size, box, alpha, blur):
    layer = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    ImageDraw.Draw(layer).ellipse(box, fill=(255, 255, 255, alpha))
    return layer.filter(ImageFilter.GaussianBlur(blur))


def rim_light(size, box, top_alpha, bottom_alpha, width):
    """边缘折射亮线：上面亮、下面弱，玻璃的转折就靠它。"""
    ring = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    draw = ImageDraw.Draw(ring)
    x0, y0, x1, y1 = box
    steps = 64
    for i in range(steps):
        t = i / steps
        a = int(top_alpha + (bottom_alpha - top_alpha) * t)
        if a <= 0:
            continue
        angle0 = 180 + 180 * t
        angle1 = 180 + 180 * (i + 1) / steps
        draw.arc([x0, y0, x1, y1], start=angle0, end=angle1, fill=(255, 255, 255, a), width=width)
    return ring


def build_ball(size, radius_ratio):
    """只画玻璃球，透明底，供前景和传统图标共用。

    radius_ratio 是球半径相对画布的比例。自适应图标的前景会被系统按不同形状裁切，
    可见区大约只有中心 66%，所以那里的球必须收进安全区，否则会被切边。
    """
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    cx = cy = size / 2
    r = size * radius_ratio
    box = [cx - r, cy - r, cx + r, cy + r]
    mask = ellipse_mask(size, box)

    # 球体：上亮下深的纵渐变，透出底色
    body = vertical_gradient(size, GLASS_BRIGHT, GLASS_DEEP, gamma=1.25)
    img = Image.alpha_composite(img, Image.composite(body, Image.new("RGBA", (size, size), (0, 0, 0, 0)), mask))

    # 中央偏上的透光核心
    core = radial_glow(size, (cx - r * 0.12, cy - r * 0.20), r * 1.15, 120)
    core.putalpha(Image.composite(core.getchannel("A"), Image.new("L", (size, size), 0), mask))
    img = Image.alpha_composite(img, core)

    # 锐利的左上高光
    hl = soft_ellipse(size, [
        cx - r * 0.66, cy - r * 0.80,
        cx - r * 0.06, cy - r * 0.34
    ], 165, size * 0.022)
    hl.putalpha(Image.composite(hl.getchannel("A"), Image.new("L", (size, size), 0), mask))
    img = Image.alpha_composite(img, hl)

    # 底部回弹光，把球撑起来
    bounce = soft_ellipse(size, [
        cx - r * 0.74, cy + r * 0.24,
        cx + r * 0.74, cy + r * 0.94
    ], 70, size * 0.040)
    bounce.putalpha(Image.composite(bounce.getchannel("A"), Image.new("L", (size, size), 0), mask))
    img = Image.alpha_composite(img, bounce)

    # 边缘折射亮线
    img = Image.alpha_composite(img, rim_light(size, box, 210, 60, max(2, int(size * 0.012))))

    return img, cx, cy, r


def draw_letter(img, size, cx, cy, r):
    font = find_font(int(r * 1.16))
    text = "J"
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    bbox = draw.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    # J 的字形重心偏右（竖笔在右侧），光学居中要往左让一点
    optical_shift = -tw * 0.06
    tx = cx - tw / 2 - bbox[0] + optical_shift
    ty = cy - th / 2 - bbox[1]

    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).text((tx, ty + size * 0.010), text, font=font, fill=(0, 0, 0, 120))
    img = Image.alpha_composite(img, shadow.filter(ImageFilter.GaussianBlur(size * 0.010)))

    draw.text((tx, ty), text, font=font, fill=(255, 255, 255, 250))
    return Image.alpha_composite(img, layer)


def draw_feather(draw, cx, cy, width, height, side, fill):
    """画一片简洁的翼羽，side 为 1（右）或 -1（左）。"""
    s = float(side)
    def point(x, y):
        return (cx + s * width * x, cy + height * y)

    def cubic(a, b, c, d, steps=8):
        points = []
        for i in range(steps + 1):
            t = i / steps
            u = 1 - t
            points.append((
                u ** 3 * a[0] + 3 * u * u * t * b[0] + 3 * u * t * t * c[0] + t ** 3 * d[0],
                u ** 3 * a[1] + 3 * u * u * t * b[1] + 3 * u * t * t * c[1] + t ** 3 * d[1],
            ))
        return points

    # 上缘收向外侧尖端，下缘回到内侧，形成连续叶片而非折角箭头。
    inner_top = point(0.04, -0.28)
    tip = point(1.00, -0.02)
    inner_bottom = point(0.08, 0.34)
    points = cubic(inner_top, point(0.35, -0.44), point(0.80, -0.27), tip)
    points += cubic(tip, point(0.82, 0.16), point(0.38, 0.42), inner_bottom)[1:]
    points += [point(0.18, 0.03), inner_top]
    draw.polygon(points, fill=fill)


def draw_wing_mark(img, size, monochrome=False):
    """画新版僚机徽章：J 主标 + 两侧双翼，保持自适应图标安全边距。"""
    draw = ImageDraw.Draw(img)
    cx = cy = size * 0.5
    wing1 = MARK_WHITE if monochrome else WING_TEAL
    wing2 = MARK_WHITE if monochrome else WING_MINT
    wing_w = size * 0.255
    wing_h = size * 0.145
    gap = size * 0.145
    draw_feather(draw, cx - gap, cy - size * 0.105, wing_w, wing_h, -1, wing1)
    draw_feather(draw, cx + gap, cy - size * 0.105, wing_w, wing_h, 1, wing1)
    draw_feather(draw, cx - gap, cy + size * 0.075, wing_w * 0.86, wing_h * 0.78, -1, wing2)
    draw_feather(draw, cx + gap, cy + size * 0.075, wing_w * 0.86, wing_h * 0.78, 1, wing2)

    radius = size * 0.37
    font = find_font(int(radius * 1.22))
    text = "J"
    bbox = draw.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    tx = cx - tw / 2 - bbox[0] + size * 0.005
    ty = cy - th / 2 - bbox[1] + size * 0.075
    draw.text((tx, ty), text, font=font, fill=MARK_WHITE)


def build_foreground(size):
    """自适应图标前景：僚机徽章收在中心安全区内，四周留给系统裁切。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mark_size = int(size * 0.78)
    mark = Image.new("RGBA", (mark_size, mark_size), (0, 0, 0, 0))
    draw_wing_mark(mark, mark_size)
    img.alpha_composite(mark, ((size - mark_size) // 2, (size - mark_size) // 2))
    return img


def build_monochrome(size):
    """Android 13+ 主题图标使用的单色前景。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mark_size = int(size * 0.78)
    mark = Image.new("RGBA", (mark_size, mark_size), (0, 0, 0, 0))
    draw_wing_mark(mark, mark_size, monochrome=True)
    img.alpha_composite(mark, ((size - mark_size) // 2, (size - mark_size) // 2))
    return img


def build_legacy(size):
    """传统图标：圆角深蓝底 + 僚机徽章。"""
    base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    radius = int(size * 0.235)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)

    bg = vertical_gradient(size, BG_TOP, BG_BOTTOM, gamma=1.1)
    base = Image.alpha_composite(base, Image.composite(bg, Image.new("RGBA", (size, size), (0, 0, 0, 0)), mask))

    # 外描边
    ring = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(ring).rounded_rectangle(
        [1, 1, size - 2, size - 2], radius=radius,
        outline=(255, 255, 255, 60), width=max(1, int(size * 0.009)))
    base = Image.alpha_composite(base, ring)

    mark = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw_wing_mark(mark, size)
    base.alpha_composite(mark)
    return base


def build_from_source():
    """从确定的最终视觉源生成所有 Android 图标尺寸，避免近似重绘。"""
    source = Image.open(SOURCE_ICON).convert("RGBA")
    made = []
    for name, mult in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        legacy_size = int(LEGACY_BASE * mult / 4)
        foreground_size = int(FOREGROUND_BASE * mult / 4)

        legacy = source.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
        p = os.path.join(folder, "ic_launcher.png")
        legacy.save(p, "PNG", optimize=True)
        made.append(p)

        # 自适应图标前景按安全区缩小，底层背景色与源图一致，裁切时不会露白边。
        mark_size = int(foreground_size * 0.78)
        mark = source.resize((mark_size, mark_size), Image.Resampling.LANCZOS)
        foreground = Image.new("RGBA", (foreground_size, foreground_size), (0, 0, 0, 0))
        foreground.alpha_composite(mark, ((foreground_size - mark_size) // 2, (foreground_size - mark_size) // 2))
        p = os.path.join(folder, "ic_launcher_foreground.png")
        foreground.save(p, "PNG", optimize=True)
        made.append(p)

        # 从同一源图提取高亮标记，生成 Android 主题图标需要的单色层。
        mask = mark.convert("L").point(lambda value: 255 if value > 92 else 0)
        mono_mark = Image.new("RGBA", (mark_size, mark_size), (255, 255, 255, 0))
        mono_mark.putalpha(mask)
        monochrome = Image.new("RGBA", (foreground_size, foreground_size), (0, 0, 0, 0))
        monochrome.alpha_composite(mono_mark, ((foreground_size - mark_size) // 2, (foreground_size - mark_size) // 2))
        p = os.path.join(folder, "ic_launcher_monochrome.png")
        monochrome.save(p, "PNG", optimize=True)
        made.append(p)

    preview = source.resize((512, 512), Image.Resampling.LANCZOS)
    p = os.path.join(ROOT, "docs", "icon.png")
    preview.save(p, "PNG", optimize=True)
    made.append(p)
    small = source.resize((48, 48), Image.Resampling.LANCZOS)
    p = os.path.join(ROOT, "docs", "icon-48.png")
    small.save(p, "PNG", optimize=True)
    made.append(p)
    return made


def main():
    if os.path.exists(SOURCE_ICON):
        for path in build_from_source():
            print("  %-58s %6d B" % (os.path.relpath(path, ROOT), os.path.getsize(path)))
        return

    made = []
    for name, mult in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)

        fg = build_foreground(int(FOREGROUND_BASE * mult / 4))
        p = os.path.join(folder, "ic_launcher_foreground.png")
        fg.save(p, "PNG", optimize=True)
        made.append(p)

        legacy = build_legacy(int(LEGACY_BASE * mult / 4))
        p = os.path.join(folder, "ic_launcher.png")
        legacy.save(p, "PNG", optimize=True)
        made.append(p)

        mono = build_monochrome(int(FOREGROUND_BASE * mult / 4))
        p = os.path.join(folder, "ic_launcher_monochrome.png")
        mono.save(p, "PNG", optimize=True)
        made.append(p)

    preview = build_legacy(512)
    p = os.path.join(ROOT, "docs", "icon.png")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    preview.save(p, "PNG", optimize=True)
    made.append(p)

    # 小尺寸预览，用来确认 48px 下还认得出
    small = build_legacy(48)
    p = os.path.join(ROOT, "docs", "icon-48.png")
    small.save(p, "PNG", optimize=True)
    made.append(p)

    for path in made:
        print("  %-58s %6d B" % (os.path.relpath(path, ROOT), os.path.getsize(path)))


if __name__ == "__main__":
    main()
