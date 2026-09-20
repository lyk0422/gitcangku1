"""统一管理一个工作副本的 Compose 环境，不执行题目或删除数据库卷。"""

import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COMMAND_TIMEOUT_SECONDS = 30
BUILD_TIMEOUT_SECONDS = 1800
HEALTH_TIMEOUT_SECONDS = 120
HEALTH_REQUEST_TIMEOUT_SECONDS = 5


def execute(arguments, *, env=None, timeout=COMMAND_TIMEOUT_SECONDS, capture=False):
    result = subprocess.run(
        arguments,
        cwd=ROOT,
        env=env,
        timeout=timeout,
        capture_output=capture,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"命令失败，退出码 {result.returncode}；请检查 Docker 状态或使用 logs 查看日志。")
    return result.stdout.strip() if capture else None


def instance_paths(instance):
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,23}", instance):
        raise ValueError("实例名须以小写字母开头，只含小写字母、数字、短横线，最多 24 字符。")
    branch = execute(["git", "rev-parse", "--abbrev-ref", "HEAD"], capture=True)
    if branch == "HEAD":
        branch = execute(["git", "rev-parse", "HEAD"], capture=True)
    identity = f"{os.path.normcase(str(ROOT))}\n{branch}"
    fingerprint = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:12]
    project = f"gsb-{fingerprint}-{instance}"
    runtime = Path.home() / ".gsb-runtime" / project
    return project, runtime


def initialize(runtime):
    runtime.mkdir(parents=True, exist_ok=True, mode=0o700)
    runtime.chmod(0o700)
    if (runtime / "compose.env").exists() and not all(
        (runtime / filename).is_file() for filename in ("db-password", "db-root-password")
    ):
        raise RuntimeError("已有实例凭据缺失，请恢复原文件；不会自动换密码连接原数据库卷。")
    for filename in ("db-password", "db-root-password"):
        secret_path = runtime / filename
        if secret_path.exists():
            if not re.fullmatch(r"[0-9a-f]{64}", secret_path.read_text(encoding="utf-8")):
                raise RuntimeError("已有数据库凭据格式异常；已保留文件，停止初始化。")
        else:
            with secret_path.open("x", encoding="utf-8") as secret_file:
                secret_file.write(secrets.token_hex(32))
        # 文件 secret 的属主无法由 Compose 改写；私有父目录保护宿主机密码，容器以非 root 只读访问。
        secret_path.chmod(0o444 if filename == "db-password" else 0o600)
    env_path = runtime / "compose.env"
    if not env_path.exists():
        env_path.write_text("# Runtime variables are supplied by dev.py.\n", encoding="utf-8")


def compose_command(project, runtime, *arguments, capture=False, timeout=COMMAND_TIMEOUT_SECONDS):
    if not all((runtime / filename).is_file() for filename in ("db-password", "db-root-password", "compose.env")):
        raise RuntimeError("该实例尚未初始化，请先执行 init；不要删除仍在使用的实例凭据。")
    env = os.environ.copy()
    env["GSB_DB_PASSWORD_FILE"] = str(runtime / "db-password")
    env["GSB_DB_ROOT_PASSWORD"] = (runtime / "db-root-password").read_text(encoding="utf-8")
    command = [
        "docker", "compose", "--project-name", project,
        "--project-directory", str(ROOT), "--env-file", str(runtime / "compose.env"),
        "--file", str(ROOT / "compose.yaml"), *arguments,
    ]
    return execute(command, env=env, capture=capture, timeout=timeout)


def health_url(project, runtime):
    binding = compose_command(project, runtime, "port", "gateway", "8080", capture=True)
    if not re.fullmatch(r"127\.0\.0\.1:\d+", binding):
        raise RuntimeError("未找到唯一的本地应用端口，请检查 status。")
    return f"http://{binding}/actuator/health"


def wait_for_health(url, timeout):
    deadline = time.monotonic() + timeout
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    last_failure = "健康状态尚未达到 UP"
    while True:
        try:
            with opener.open(url, timeout=HEALTH_REQUEST_TIMEOUT_SECONDS) as response:
                health = json.load(response)
            if isinstance(health, dict) and health.get("status") == "UP":
                return health
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as error:
            # 启动期间允许短暂失败，记录最后的错误类型，并在到期后明确报告。
            last_failure = type(error).__name__
        if time.monotonic() >= deadline:
            raise RuntimeError(f"健康检查未通过（{last_failure}）；环境已保留，请使用 logs 定位错误。")
        time.sleep(1)


def doctor():
    if shutil.which("docker") is None:
        raise RuntimeError("未找到 Docker，请安装 Docker Desktop 并启用 Linux 容器。")
    execute(["docker", "--version"])
    execute(["docker", "compose", "version"])
    engine = execute(["docker", "info", "--format", "{{.OSType}}"], capture=True, timeout=15)
    if engine != "linux":
        raise RuntimeError("需要可用的 Linux Docker 引擎，请启动 Docker Desktop。")
    print("Docker Linux 引擎可用。")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "doctor", "check", "up", "status", "logs", "demo", "down"))
    parser.add_argument("--instance", default="dev", help="运行实例，默认 dev；A/B 可分别使用 q01-a、q01-b")
    parser.add_argument("--fresh", action="store_true", help="仅用于 up；拒绝复用已有实例或数据库")
    args = parser.parse_args()
    if args.fresh and args.command != "up":
        parser.error("--fresh 只能与 up 一起使用。")
    project, runtime = instance_paths(args.instance)

    if args.command == "doctor":
        doctor()
        return
    if args.fresh:
        require_fresh(project, runtime)
    if args.command in ("init", "check", "up"):
        initialize(runtime)
    if args.command == "init":
        print(f"已初始化实例 {project}；凭据保存在源码之外，不输出内容。")
        return
    if args.command == "check":
        compose_command(project, runtime, "config", "--quiet")
        print("Compose 配置检查通过；未构建镜像、未启动容器。")
        return
    if args.command == "up":
        doctor()
        compose_command(project, runtime, "config", "--quiet")
        compose_command(
            project, runtime, "up", "--detach", "--build", "--wait", "--wait-timeout", "180",
            timeout=BUILD_TIMEOUT_SECONDS,
        )
        url = health_url(project, runtime)
        wait_for_health(url, HEALTH_TIMEOUT_SECONDS)
        print(f"运行实例：{project}")
        print(f"应用与数据库健康检查通过：{url}")
        return
    if args.command == "demo":
        url = health_url(project, runtime)
        health = wait_for_health(url, HEALTH_REQUEST_TIMEOUT_SECONDS)
        print(f"GET {url}")
        print(json.dumps(health, ensure_ascii=False))
        print("这里只验证基础工程与数据库连接，不代表任何题目业务已经完成。")
        return
    if args.command == "status":
        compose_command(project, runtime, "ps", "--all")
        return
    if args.command == "logs":
        compose_command(project, runtime, "logs", "--no-color", "--tail", "100")
        return
    if args.command == "down":
        compose_command(project, runtime, "down", timeout=90)
        print("容器已停止，数据库卷和实例凭据已保留。")


def require_fresh(project, runtime):
    if runtime.exists():
        raise RuntimeError("该实例已有本地运行记录，请更换 --instance；不会清除已有数据。")
    label = f"label=com.docker.compose.project={project}"
    for arguments in (
        ["docker", "ps", "--all", "--quiet", "--filter", label],
        ["docker", "volume", "ls", "--quiet", "--filter", label],
        ["docker", "network", "ls", "--quiet", "--filter", label],
    ):
        if execute(arguments, capture=True):
            raise RuntimeError("该实例已有 Docker 资源，请更换 --instance；不会复用或删除。")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    try:
        main()
    except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as error:
        print(f"操作失败：{error}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print("操作已中断；容器可能已创建，请使用 status 检查。", file=sys.stderr)
        sys.exit(130)
