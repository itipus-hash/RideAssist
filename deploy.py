"""构建 APK 并更新到项目 apk/ 目录（随 git 提交到 GitHub）。

用法：
    python deploy.py            # 构建 Debug 并更新
    python deploy.py --release  # 构建 Release 并更新
    python deploy.py --apk-only # 不重新构建，只更新现有 APK

命名规则（固定名，覆盖式，目录里永远只有一个最新包）：
    apk/RideAssist-v<版本>-<变体>.apk
"""
import os
import re
import shutil
import subprocess
import sys

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
APK_DIR = os.path.join(PROJECT_DIR, "apk")     # 项目内目录，随 git 提交
PROJECT_NAME = "RideAssist"

JAVA_HOME = r"C:/Program Files/Android/Android Studio/jbr"
GRADLE = None
for p in [os.path.join(PROJECT_DIR, "gradlew.bat")]:
    if os.path.exists(p):
        GRADLE = p
        break


def find_gradle():
    if GRADLE:
        return GRADLE
    import glob
    base = os.path.expanduser(r"~\.gradle\wrapper\dists")
    for pattern in ("gradle-8.14-all/*/gradle-8.14/bin/gradle.bat",
                    "gradle-8.13-all/*/gradle-8.13/bin/gradle.bat"):
        hits = glob.glob(os.path.join(base, pattern))
        if hits:
            return hits[0]
    return None


def read_version():
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
    d = os.path.join(PROJECT_DIR, "app", "build", "outputs", "apk", variant)
    if not os.path.isdir(d):
        return None
    apks = [f for f in os.listdir(d) if f.endswith(".apk")]
    if not apks:
        return None
    apks.sort(key=lambda f: ("unsigned" not in f, f))
    return os.path.join(d, apks[0])


def copy_to_apk_dir(apk_path, variant):
    """覆盖式更新项目 apk/ 目录（固定文件名，只保留一个最新包）。"""
    os.makedirs(APK_DIR, exist_ok=True)

    version = read_version()
    named = f"{PROJECT_NAME}-v{version}-{variant}.apk"

    # 清掉该变体的旧版本文件（版本号升级后旧名残留）
    for f in os.listdir(APK_DIR):
        if f.startswith(PROJECT_NAME) and f.endswith(f"-{variant}.apk") and f != named:
            os.remove(os.path.join(APK_DIR, f))

    dst = os.path.join(APK_DIR, named)
    shutil.copy2(apk_path, dst)

    size = os.path.getsize(dst) / 1024 / 1024
    print(f"[3/3] 已更新：apk/{named}  ({size:.1f} MB)")

    # 自动提交到 git（失败不影响结果，用户可手动 commit）
    try:
        subprocess.run(["git", "add", f"apk/{named}"], cwd=PROJECT_DIR,
                       capture_output=True)
        r = subprocess.run(
            ["git", "commit", "-q", "-m", f"build: 更新 apk/{named}"],
            cwd=PROJECT_DIR, capture_output=True, text=True)
        if r.returncode == 0:
            print("   已自动提交，记得 Push 到 GitHub")
        else:
            print("   （无变更需要提交）")
    except Exception:
        pass
    return dst


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

    copy_to_apk_dir(apk, variant)


if __name__ == "__main__":
    main()
