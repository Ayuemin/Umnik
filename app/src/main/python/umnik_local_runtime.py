import contextlib
import io
import json
import os
import sys
import traceback

_ROOT = None
_RUNTIME_ROOTS = tuple(
    os.path.realpath(p)
    for p in list(sys.path) + [sys.prefix, getattr(sys, "base_prefix", sys.prefix)]
    if isinstance(p, str) and p
)


def _inside(path, root):
    try:
        return os.path.commonpath([os.path.realpath(path), os.path.realpath(root)]) == os.path.realpath(root)
    except Exception:
        return False


def _audit(event, args):
    root = _ROOT
    if not root:
        return

    if event == "open" and args:
        path = args[0]
        if isinstance(path, int):
            return
        if not isinstance(path, (str, bytes, os.PathLike)):
            return
        path = os.fspath(path)
        if isinstance(path, bytes):
            path = os.fsdecode(path)
        absolute = path if os.path.isabs(path) else os.path.join(os.getcwd(), path)
        mode = ""
        if len(args) > 1 and isinstance(args[1], str):
            mode = args[1]
        if _inside(absolute, root):
            return
        # Python itself must still be able to import its bundled standard library.
        if not any(flag in mode for flag in ("w", "a", "x", "+")):
            for allowed in _RUNTIME_ROOTS:
                if _inside(absolute, allowed):
                    return
        raise PermissionError("Local Shell Python can access only the task workspace")

    if event in ("os.system", "subprocess.Popen"):
        raise PermissionError("Start external commands through Local Shell tools, not from Python")

    if event in ("socket.connect", "socket.getaddrinfo"):
        raise PermissionError("Network access from Python is disabled; use the Local Shell network gateway")


if not getattr(sys, "_umnik_local_audit_installed", False):
    sys.addaudithook(_audit)
    sys._umnik_local_audit_installed = True


def run(code, workspace):
    global _ROOT
    root = os.path.realpath(workspace)
    os.makedirs(root, exist_ok=True)
    old_cwd = os.getcwd()
    old_root = _ROOT
    _ROOT = root
    stdout = io.StringIO()
    stderr = io.StringIO()
    globals_dict = {
        "__name__": "__main__",
        "__file__": "<umnik-local-shell>",
        "WORKSPACE": root,
    }
    ok = True
    error = None
    try:
        os.chdir(root)
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            exec(compile(code, "<umnik-local-shell>", "exec"), globals_dict, globals_dict)
    except BaseException as exc:
        ok = False
        error = f"{type(exc).__name__}: {exc}"
        traceback.print_exc(file=stderr)
    finally:
        os.chdir(old_cwd)
        _ROOT = old_root

    return json.dumps(
        {
            "ok": ok,
            "stdout": stdout.getvalue(),
            "stderr": stderr.getvalue(),
            "error": error,
        },
        ensure_ascii=False,
    )
