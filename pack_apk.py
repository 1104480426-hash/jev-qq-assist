"""把 dex、native 库和 assets 写入 APK。

不用 aapt2 的 -A 参数打包 assets：Windows 上 aapt2 会用反斜杠生成条目名
（models\\bge-small-zh\\x.json），AssetManager 只认正斜杠，于是整个路径被当成
一个文件名，open() 必然 FileNotFoundException。这里用 zipfile 按规范写正斜杠路径。

用法: python pack_apk.py <base.apk> <out.apk> <dex_dir> <native_dir> <assets_dir>
"""
import os
import shutil
import sys
import zipfile


def files_under(root):
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace("\\", "/")
            yield full, rel


def main():
    base, out, dex_dir, native_dir, assets_dir = sys.argv[1:6]
    shutil.copy(base, out)

    added = []
    with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        existing = set(z.namelist())

        for full, rel in files_under(dex_dir):
            if rel not in existing:
                z.write(full, rel)
                added.append(rel)

        for full, rel in files_under(native_dir):
            if rel not in existing:
                z.write(full, rel)
                added.append(rel)

        if os.path.isdir(assets_dir):
            for full, rel in files_under(assets_dir):
                arc = "assets/" + rel
                if arc not in existing:
                    z.write(full, arc)
                    added.append(arc)

    print("packed %d entries into %s" % (len(added), os.path.basename(out)))
    for name in added:
        print("   +", name)


if __name__ == "__main__":
    main()
