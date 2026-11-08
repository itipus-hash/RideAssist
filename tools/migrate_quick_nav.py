#!/usr/bin/env python3
"""
⭐ 快捷导航数据格式迁移：把历史三代格式统一成最新的 {value,label} 两个字段。

历史格式（APP 旧版本写下的）：
    {value, label}                        ← 已是最新
    {dest, label, uri}                    ← uri 是完整链接时用 uri，否则用 dest
    {name, uri}                           ← uri 是完整链接时用 uri，否则用 name

最新格式（唯一）：
    {value, label}
      value = 链接 / 地名 / 坐标（"含 ://" 判定为直达链接）
      label = 按钮显示名（空则显示 value）

用法（连着设备跑）：
    python tools/migrate_quick_nav.py            # 迁移设备上的数据
    python tools/migrate_quick_nav.py --dry      # 只看会变成什么样，不写

⚠️ 迁移是**幂等**的：已经是 {value,label} 的条目原样保留，不会重复跑出问题。
"""
import argparse
import html
import json
import re
import subprocess
import sys

PKG = "io.github.supit.rideassist.debug"
PREFS = "shared_prefs/yybms_prefs.xml"


def adb(*args, binary=False):
    cmd = ["adb"] + list(args)
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode("utf-8", "ignore"))
    return r.stdout if binary else r.stdout.decode("utf-8", "ignore")


def normalize(raw):
    """把一条历史条目转成 {value,label}"""
    value = raw.get("value", "") or ""
    uri = raw.get("uri", "") or ""
    dest = raw.get("dest", "") or raw.get("name", "") or ""
    label = raw.get("label", "") or ""
    if not value:
        if uri and "://" in uri:
            value = uri
        elif dest:
            value = dest
        else:
            value = uri
    return {"value": value, "label": label}


def migrate_list(text):
    try:
        arr = json.loads(text)
    except Exception:
        return None, False
    out = []
    changed = False
    for item in arr:
        if not isinstance(item, dict):
            continue
        new = normalize(item)
        if new != item:
            changed = True
        out.append(new)
    return out, changed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry", action="store_true", help="只打印，不写回")
    ap.add_argument("--pkg", default=PKG)
    args = ap.parse_args()

    adb("shell", "am", "force-stop", args.pkg)
    xml = adb("shell", "run-as", args.pkg, "cat", PREFS)
    m = re.search(r'<string name="quick_nav_list">(.*?)</string>', xml, re.S)
    if not m:
        print("没找到 quick_nav_list，无需迁移")
        return
    raw = html.unescape(m.group(1))
    new_list, changed = migrate_list(raw)
    if new_list is None:
        print("quick_nav_list 不是合法 JSON，跳过")
        return
    for i, e in enumerate(new_list):
        print("  %d %s" % (i, json.dumps(e, ensure_ascii=False)))
    if not changed:
        print("已是最新格式，无需改动")
        return
    if args.dry:
        print("[dry] 上面是迁移后的样子，未写回")
        return
    payload = json.dumps(new_list, ensure_ascii=False, separators=(",", ":"))
    payload_xml = html.escape(payload, quote=True)
    new_xml = xml[: m.start(1)] + payload_xml + xml[m.end(1):]
    with open("quick_nav_migrated.xml", "w", encoding="utf-8") as f:
        f.write(new_xml)
    adb("push", "quick_nav_migrated.xml", "/data/local/tmp/qn.xml")
    adb(
        "shell",
        "cat /data/local/tmp/qn.xml | run-as %s sh -c 'cat > %s'" % (args.pkg, PREFS),
    )
    print("已写回：%d 个条目，全部为 {value,label} 格式" % len(new_list))


if __name__ == "__main__":
    main()
