#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 main + 22 个版本分支用 GitHub Git Data API 推送（沙箱直连 github.com 不通时使用）。

用法:
    python3 push_all.py                 # 推全部分支
    python3 push_all.py forge-1.20.1    # 只推指定分支
    python3 push_all.py --dry-run
"""
import base64
import json
import os
import subprocess
import sys
import time

REPO = 'Zxin-Pro/unbounded-height'
PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(PROJ, 'tools', 'out')
MAIN = os.path.join(PROJ, 'main')

AUTHOR = ('Zxin-Pro', 'Zxin-Pro@users.noreply.github.com')
DATE = '2026-10-14T13:00:00+08:00'

COMMIT_MSG = """feat: Unbounded Height — initial implementation

Minecraft 1.20.1-26.3 (Forge / NeoForge / Fabric) height-limit removal mod.

- Core: single Mixin injection on DimensionType#minY/height/logicalHeight
  propagates through LevelHeightAccessor, ChunkAccess, Heightmap and
  ChunkSerializer; vanilla registry sync keeps clients correct with no
  custom payload required for the height itself.
- Dimension compatibility: ServerLevel ctor hook registers every real
  dimension (datapack and modded alike) via DimensionType identity map.
- /height command (admin), JSON config, hard-limit boundary protection.
"""

_blob_cache = {}
_CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '.blob_cache.json')
if os.path.exists(_CACHE_FILE):
    try:
        _blob_cache = json.load(open(_CACHE_FILE, encoding='utf-8'))
    except Exception:
        _blob_cache = {}
_stats = {'blobs': 0, 'cached': 0}


def save_cache():
    try:
        json.dump(_blob_cache, open(_CACHE_FILE, 'w', encoding='utf-8'))
    except Exception:
        pass


def sh(cwd, *args, check=True):
    r = subprocess.run(list(args), cwd=cwd, capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError('%s in %s: %s' % (args, cwd, r.stderr.decode('utf-8', 'replace')[:500]))
    return r.stdout


def gh(path, method='POST', body=None, fatal=True):
    cmd = ['gh', 'api', 'repos/%s/%s' % (REPO, path), '--method', method]
    inp = None
    if body is not None:
        cmd += ['--input', '-']
        inp = json.dumps(body).encode('utf-8')
    for attempt in range(4):
        r = subprocess.run(cmd, input=inp, capture_output=True)
        if r.returncode == 0:
            return json.loads(r.stdout.decode('utf-8') or '{}')
        err = r.stderr.decode('utf-8', 'replace')
        if 'secondary rate limit' in err or 'rate limit' in err or 'abuse' in err:
            time.sleep(8 * (attempt + 1))
            continue
        if fatal:
            print('   API 失败 %s: %s' % (path, err[:400]))
            return None
        return None
    return None


def local_commit(branch_dir, branch):
    """本地建一个干净的提交，返回 (commit_sha, [(mode, sha, path)], tree_sha)。"""
    if os.path.isdir(os.path.join(branch_dir, '.git')):
        sh(branch_dir, 'git', 'init', '-q') if False else None
    sh(branch_dir, 'git', 'init', '-q')
    sh(branch_dir, 'git', 'add', '-A')
    sh(branch_dir, 'git',
       '-c', 'user.name=' + AUTHOR[0], '-c', 'user.email=' + AUTHOR[1],
       '-c', 'commit.gpgsign=false',
       'commit', '-q', '--allow-empty', '-m', COMMIT_MSG)
    raw = sh(branch_dir, 'git', 'ls-tree', '-r', 'HEAD').decode('utf-8')
    entries = []
    for line in raw.splitlines():
        meta, path = line.split('\t', 1)
        mode, _typ, sha = meta.split()
        entries.append((mode, sha, path))
    tree = sh(branch_dir, 'git', 'rev-parse', 'HEAD^{tree}').decode().strip()
    commit = sh(branch_dir, 'git', 'rev-parse', 'HEAD').decode().strip()
    return commit, entries, tree


def push_branch(branch, branch_dir, dry=False):
    commit, entries, tree = local_commit(branch_dir, branch)
    print('  %-18s %d 文件, commit=%s' % (branch, len(entries), commit[:8]))
    if dry:
        return commit

    tree_items = []
    for mode, sha, path in entries:
        rsha = _blob_cache.get(sha)
        if rsha is None:
            data = sh(branch_dir, 'git', 'cat-file', 'blob', sha)
            res = gh('git/blobs', body={'content': base64.b64encode(data).decode(), 'encoding': 'base64'})
            if res is None:
                return None
            rsha = res['sha']
            _blob_cache[sha] = rsha
            _stats['blobs'] += 1
        else:
            _stats['cached'] += 1
        tree_items.append({'path': path, 'mode': mode, 'type': 'blob', 'sha': rsha})

    res = gh('git/trees', body={'tree': tree_items})
    if res is None:
        return None
    remote_tree = res['sha']
    if remote_tree != tree:
        print('     ! tree 不一致 remote=%s local=%s（继续）' % (remote_tree[:8], tree[:8]))

    body = {
        'message': COMMIT_MSG,
        'tree': remote_tree,
        'parents': [],
        'author': {'name': AUTHOR[0], 'email': AUTHOR[1], 'date': DATE},
        'committer': {'name': AUTHOR[0], 'email': AUTHOR[1], 'date': DATE},
    }
    res = gh('git/commits', body=body)
    if res is None:
        return None
    remote_commit = res['sha']

    ref = 'git/refs/heads/' + branch
    existing = gh(ref, method='GET', fatal=False)
    if existing:
        gh(ref, method='PATCH', body={'sha': remote_commit, 'force': True})
    else:
        gh('git/refs', body={'ref': 'refs/heads/' + branch, 'sha': remote_commit})
    print('     ✔ refs/heads/%s = %s' % (branch, remote_commit[:8]))
    return remote_commit


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    dry = '--dry-run' in sys.argv
    branches = []
    branches.append(('main', MAIN))
    if os.path.isdir(OUT):
        for name in sorted(os.listdir(OUT)):
            if os.path.isdir(os.path.join(OUT, name)):
                branches.append((name, os.path.join(OUT, name)))
    if args:
        branches = [b for b in branches if b[0] in args]

    print('仓库: %s  分支数: %d  dry_run=%s' % (REPO, len(branches), dry))
    ok = []
    for branch, d in branches:
        r = push_branch(branch, d, dry)
        if r:
            ok.append(branch)
    print('\n完成 %d/%d  新建 blob=%d 复用=%d' % (len(ok), len(branches), _stats['blobs'], _stats['cached']))
    save_cache()


if __name__ == '__main__':
    main()
