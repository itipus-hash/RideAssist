"""构建 APK 并复制到 D:\\NAS。

用法：
    python deploy.py            # 构建 Debug 并复制
    python deploy.py --release  # 构建 Release 并复制
    python deploy.py --apk-only # 不重新构建，只复制现有 APK

命名规则：<项目名>_v<版本>_<日期>_<变体>.apk
另外维护一个 <项目名>_latest_<变体>.apk 作为"最新可用"副本。
"""
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
NAS_DIR = r"D:\NAS\RideAssist"
PROJECT_NAME = "RideAssist"

JAVA_HOME = r"C:\Program Files\Android\Android Studio\jbr"
GRADLE = None
# 优先用 wrapper，找不到再回退到本机缓存
for p in [
    os.path.join(PROJECT_DIR, "gradlew.bat"),
]:
    if os.path.exists(p):
        GRADLE = p
        break


def find_gradle():
    """定位可用的 Gradle"""
    if GRADLE:
        return GRADLE
    # 回退：扫描本机 wrapper 缓存里的 gradle
    import glob
    base = os.path.expanduser(r"~\.gradle\wrapper\dists")
    for pattern in ("gradle-8.14-all/*/gradle-8.14/bin/gradle.bat",
                    "gradle-8.13-all/*/gradle-8.13/bin/gradle.bat"):
        hits = glob.glob(os.path.join(base, pattern))
        if hits:
            return hits[0]
    return None


def read_version():
    """从 build.gradle.kts 读取 versionName"""
    cfg = os.path.join(PROJECT_DIR, "app", "build.gradle.kts")
    with open(cfg, encoding="utf-8") as f:
        m = re.search(r'versionName\s*=\s*"([^"]+)"', f.read())
    return m.group(1) if m else "1.0.0"


def run_build(variant):
    gradle = find_gradle()
    if not gradle:
        print("[错误] 找不到 Gradle，请用 Android Studio 构建后用 --apk-only")
        return None

    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME
    task = "assembleDebug" if variant == "debug" else "assembleRelease"

    print(f"[1/3] 构建 {task} ...")
    r = subprocess.run(
        [gradle, "--console=plain", task],
        cwd=PROJECT_DIR, env=env,
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    out = (r.stdout or "") + (r.stderr or "")
    for line in out.splitlines():
        if "BUILD SUCCESSFUL" in line or "BUILD FAILED" in line or line.startswith("e: "):
            print("   " + line)
    if r.returncode != 0:
        print("[失败] 构建未通过")
        return None
    return variant


def find_apk(variant):
    suffix = "debug" if variant == "debug" else "release"
    d = os.path.join(PROJECT_DIR, "app", "build", "outputs", "apk", suffix)
    if not os.path.isdir(d):
        return None
    apks = [f for f in os.listdir(d) if f.endswith(".apk")]
    if not apks:
        return None
    # 优先取 unsigned 的正式包（若存在），否则取第一个
    apks.sort(key=lambda f: ("unsigned" not in f, f))
    return os.path.join(d, apks[0])


def copy_to_nas(apk_path, variant):
    """复制到 NAS。

    命名规则（用户指定）：
      - 版本包   {项目名}_v{版本}_{时间戳}_{变体}.apk        例 ..._20261009_0035_debug.apk
      - 最新包   {项目名}_v{版本}_{时间戳}_{变体}_latest.apk  例 ..._20261009_0035_debug_latest.apk

    每次归档时，若已存在**旧的** _latest 文件，
    会把它**改名为普通版本包名**（去掉 _latest），
    这样 latest 永远只指向最新一个，历史包仍以版本包形式保留。
    """
    if not os.path.exists(NAS_DIR) is False and not os.path.isdir(NAS_DIR):
        os.makedirs(NAS_DIR, exist_ok=True)

    version = read_version()
    # 时间戳带下划线：20261009_0035
    ts = datetime.now().strftime("%Y%m%d_%H%M")
    stem = f"{PROJECT_NAME}_v{version}_{ts}_{variant}"
    named = f"{stem}.apk"            # 版本包
    latest = f"{stem}_latest.apk"    # 最新包（同一份内容，多一个_latest 便于识别）

    # ⭐ 把 NAS 上**所有**旧的 _latest 文件改名成普通版本包（去掉 _latest 后缀），
    #   这样 latest 永远只指向最新一个，历史包仍以版本包形式保留。
    #   ⚠️ 必须按目录扫描而非判断同名文件：latest 带时间戳，
    #   每次归档的文件名都不同，判断 os.path.exists(latest) 永远为 False，
    #   旧latest 会无限堆积（实测踩到）。
    import glob
    for old_latest in glob.glob(os.path.join(NAS_DIR, f"*_{variant}_latest.apk")):
        renamed = old_latest[:-len("_latest.apk")] + ".apk"
        if os.path.exists(renamed):
            os.remove(old_latest)      # 同名版本包已存在，直接删掉重复的 latest
        else:
            os.replace(old_latest, renamed)

    dst_named = os.path.join(NAS_DIR, named)
    dst_latest = os.path.join(NAS_DIR, latest)
    shutil.copy2(apk_path, dst_named)
    shutil.copy2(apk_path, dst_latest)

    size = os.path.getsize(dst_named) / 1024 / 1024
    print(f"[3/3] 已复制到 NAS：{NAS_DIR}")
    print(f"   版本包 {named}  ({size:.1f} MB)")
    print(f"   最新包 {latest}")
    return dst_named


def main():
    args = sys.argv[1:]
    variant = "release" if "--release" in args else "debug"

    if "--apk-only" not in args:
        if run_build(variant) is None:
            sys.exit(1)
    else:
        print("[1/3] 跳过构建（--apk-only）")

    apk = find_apk(variant)
    if not apk:
        print(f"[错误] 找不到 {variant} APK")
        sys.exit(1)
    print(f"[2/3] 产物：{apk}")

    copy_to_nas(apk, variant)


if __name__ == "__main__":
    main()