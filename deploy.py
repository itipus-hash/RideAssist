"""构建 APK 并更新到项目 apk/ 目录（随 git 提交到 GitHub）。

用法：
    python deploy.py            # 构建 Debug 并更新
    python deploy.py --release  # 构建 Release 并更新
    python deploy.py --apk-only # 不重新构建，只更新现有 APK

命名规则（固定名，覆盖式，目录里永远只有一个最新包）：
    apk/RideAssist-v<版本>.apk

⚠️ 文件名**不带 -debug / -release 变体后缀**（用户 2026-11-08 要求去掉
「-debug」这个看起来不正式的尾巴）。注意这只是**文件名**：
包的 applicationId 仍然是 `io.github.supit.rideassist.debug`
（debug buildType 的 `applicationIdSuffix`），改了它等于换一个 App，
已装设备必须卸载重装、所有偏好清零 —— 所以命名简化不碰包名。
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


def bump_version():
    """⭐ 版本号自动递增：1.0.1 → 1.0.2（patch 位 +1，写回 build.gradle.kts）"""
    cfg = os.path.join(PROJECT_DIR, "app", "build.gradle.kts")
    with open(cfg, encoding="utf-8") as f:
        content = f.read()
    m = re.search(r'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"', content)
    if not m:
        return
    major, minor, patch = int(m.group(1)), int(m.group(2)), int(m.group(3))
    new = f"{major}.{minor}.{patch + 1}"
    content = content[:m.start()] + f'versionName = "{new}"' + content[m.end():]
    with open(cfg, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"[0/3] 版本号自动递增 → {new}")


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
    # ⭐ 文件名不带变体后缀（用户要求去掉 -debug）。
    #    目录里本来就只留一个包，所以 debug / release 同名覆盖是预期行为。
    named = f"{PROJECT_NAME}-v{version}.apk"

    # 清掉所有旧包：包括历史遗留的 `-debug` / `-release` 命名，
    # 否则 `apk/` 里会同时躺着 `RideAssist-v1.0.11.apk` 和
    # `RideAssist-v1.0.11-debug.apk` 两个内容相同的文件。
    for f in os.listdir(APK_DIR):
        if f.startswith(PROJECT_NAME) and f.endswith(".apk") and f != named:
            os.remove(os.path.join(APK_DIR, f))

    dst = os.path.join(APK_DIR, named)
    shutil.copy2(apk_path, dst)

    size = os.path.getsize(dst) / 1024 / 1024
    print(f"[3/3] 已更新：apk/{named}  ({size:.1f} MB)")

    # ⭐ 同步生成 update.json（应用内更新检查的版本信息源）
    #   --notes 可重复传多条，弹窗里每条一行；history 保留各历史版本内容（跨版本累积显示）
    import json
    notes_list = []
    i = 0
    while i < len(sys.argv):
        if sys.argv[i] == "--notes" and i + 1 < len(sys.argv):
            notes_list.append(sys.argv[i + 1])
            i += 2
        else:
            i += 1
    notes = "\n".join(f"{n}. {t}" for n, t in enumerate(notes_list, 1))

    json_path = os.path.join(APK_DIR, "update.json")
    history = []
    if os.path.exists(json_path):
        try:
            with open(json_path, encoding="utf-8") as f:
                history = json.load(f).get("history", [])
        except Exception:
            history = []
    history = [h for h in history if h.get("version") != version]
    history.append({"version": version, "notes": notes})
    history.sort(key=lambda h: [int(x) for x in h["version"].split(".")])

    update = {"version": version, "notes": notes, "apk": named, "history": history}
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(update, f, ensure_ascii=False, indent=2)
    print(f"   update.json -> v{version}（history {len(history)} 条）")

    # 自动提交到 git（失败不影响结果，用户可手动 commit）
    try:
        subprocess.run(["git", "add", "apk/"], cwd=PROJECT_DIR,
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
        bump_version()   # ⭐ 每次重新构建自动递增版本号（patch 位 +1）
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
