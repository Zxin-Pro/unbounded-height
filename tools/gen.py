#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Unbounded Height · 多版本分支生成器
把 templates/ 下的模板按 22 个目标（加载器 + MC 版本）展开成完整 Gradle 项目。

用法：
    python3 gen.py                # 生成全部 22 个分支到 out/<branch>/
    python3 gen.py forge-1.20.1   # 只生成指定分支
"""
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
TPL = os.path.join(ROOT, 'templates')
OUT = os.path.join(ROOT, 'out')
WRAP = os.path.join(ROOT, 'wrapper')          # gradle-wrapper.jar / gradlew
GH_REPO = 'Zxin-Pro/unbounded-height'

# ----------------------------------------------------------------------------
# 版本表：数字全部来自官方 Maven / Meta / MDK 元数据（见 research/报告.md）
# ----------------------------------------------------------------------------
TARGETS = [
    # ---- Forge (ForgeGradle 6) ----
    dict(loader='forge', mc='1.20.1', java=17, gradle='8.14.5', forge='47.4.27'),
    dict(loader='forge', mc='1.20.2', java=17, gradle='8.14.5', forge='48.1.0'),
    dict(loader='forge', mc='1.20.4', java=17, gradle='8.14.5', forge='49.2.11'),
    # ---- NeoForge (ModDevGradle 2.0.148) ----
    dict(loader='neoforge', mc='1.20.6', java=21, gradle='9.2.1', neo='20.6.141', parchment='2024.06.16'),
    dict(loader='neoforge', mc='1.21.1', java=21, gradle='9.2.1', neo='21.1.259', parchment='2024.11.17'),
    dict(loader='neoforge', mc='1.21.4', java=21, gradle='9.2.1', neo='21.4.158', parchment='2025.03.23'),
    dict(loader='neoforge', mc='1.21.8', java=21, gradle='9.2.1', neo='21.8.54', parchment='2025.09.14'),
    dict(loader='neoforge', mc='1.21.11', java=21, gradle='9.2.1', neo='21.11.45', parchment='2025.12.20'),
    dict(loader='neoforge', mc='26.1', java=25, gradle='9.2.1', neo='26.1.0.19-beta', parchment=None),
    dict(loader='neoforge', mc='26.2', java=25, gradle='9.2.1', neo='26.2.0.90', parchment=None),
    dict(loader='neoforge', mc='26.3', java=25, gradle='9.2.1', neo='26.3.0.69-beta', parchment=None),
    # ---- Fabric (Loom 1.18-SNAPSHOT) ----
    dict(loader='fabric', mc='1.20.1', java=17, gradle='9.7.1', loom='net.fabricmc.fabric-loom-remap', api='0.92.12+1.20.1'),
    dict(loader='fabric', mc='1.20.2', java=17, gradle='9.7.1', loom='net.fabricmc.fabric-loom-remap', api='0.91.6+1.20.2'),
    dict(loader='fabric', mc='1.20.4', java=17, gradle='9.7.1', loom='net.fabricmc.fabric-loom-remap', api='0.97.3+1.20.4'),
    dict(loader='fabric', mc='1.20.6', java=21, gradle='9.7.1', loom='net.fabricmc.fabric-loom-remap', api='0.100.8+1.20.6'),
    dict(loader='fabric', mc='1.21.1', java=21, gradle='9.7.1', loom='net.fabricmc.fabric-loom-remap', api='0.116.17+1.21.1'),
    dict(loader='fabric', mc='1.21.4', java=21, gradle='9.7.1', loom='net.fabricmc.fabric-loom-remap', api='0.119.4+1.21.4'),
    dict(loader='fabric', mc='1.21.8', java=21, gradle='9.7.1', loom='net.fabricmc.fabric-loom-remap', api='0.136.1+1.21.8'),
    dict(loader='fabric', mc='1.21.11', java=21, gradle='9.7.1', loom='net.fabricmc.fabric-loom-remap', api='0.141.6+1.21.11'),
    dict(loader='fabric', mc='26.1', java=25, gradle='9.7.1', loom='net.fabricmc.fabric-loom', api='0.145.1+26.1'),
    dict(loader='fabric', mc='26.2', java=25, gradle='9.7.1', loom='net.fabricmc.fabric-loom', api='0.161.0+26.2'),
    dict(loader='fabric', mc='26.3', java=25, gradle='9.7.1', loom='net.fabricmc.fabric-loom', api='0.162.0+26.3'),
]

LOADER_MIN = {'forge': '47', 'neoforge': '20.6', 'fabric': None}


def mc_range(mc):
    """mods.toml / fabric.mod.json 的 minecraft 依赖范围。"""
    if mc.startswith('26.'):
        return '[' + mc + ']'
    parts = mc.split('.')
    major, minor = int(parts[0]), int(parts[1])
    return '[' + mc + ',' + str(major) + '.' + str(minor + 1) + ')'


def pack_format(mc):
    """pack.mcmeta 的 pack_format。"""
    table = {'1.20.1': 15, '1.20.2': 18, '1.20.4': 22, '1.20.6': 41, '1.21.1': 48,
             '1.21.4': 61, '1.21.8': 74, '1.21.11': 84}
    return table.get(mc, 90)


def mc_code(mc):
    """MC 版本 → 可比较整数：1.20.1 -> 12001，26.3 -> 26003。"""
    parts = mc.split('.')
    major = int(parts[0])
    minor = int(parts[1]) if len(parts) > 1 else 0
    patch = int(parts[2]) if len(parts) > 2 else 0
    if major == 1:
        return 10000 + minor * 100 + patch
    return major * 1000 + minor * 100 + patch


def build_flags(t):
    flags = {
        'MC': mc_code(t['mc']),
        'JAVA': t['java'],
        'LOADER_FORGE': t['loader'] == 'forge',
        'LOADER_NEOFORGE': t['loader'] == 'neoforge',
        'LOADER_FABRIC': t['loader'] == 'fabric',
    }
    return flags


COND_RE = re.compile(r'^(MC|JAVA)\s*(>=|<=|>|<|==)\s*(\d+)$')


def eval_cond(cond, flags):
    cond = cond.strip()
    if cond.startswith('!'):
        return not eval_cond(cond[1:], flags)
    if cond in ('LOADER_FORGE', 'LOADER_NEOFORGE', 'LOADER_FABRIC'):
        return bool(flags[cond])
    m = COND_RE.match(cond)
    if not m:
        raise ValueError('无法解析条件: ' + cond)
    key, op, value = m.group(1), m.group(2), int(m.group(3))
    left = flags[key]
    return {
        '>=': left >= value, '<=': left <= value,
        '>': left > value, '<': left < value, '==': left == value,
    }[op]


def preprocess(text, flags):
    """处理 //#if / //#else / //#endif，返回 (内容, 是否被 @only 排除)。"""
    lines = text.split('\n')
    out = []
    # @only 文件级指令
    only = None
    keep = []
    for line in lines:
        stripped = line.strip()
        if stripped.startswith('//@only '):
            only = stripped[len('//@only '):].strip()
            continue
        keep.append(line)
    if only is not None and not eval_cond(only, flags):
        return None

    stack = []  # [(enabled, taken_branch_already)]
    active = True
    for line in keep:
        s = line.strip()
        if s.startswith('//#if '):
            parent_ok = active
            ok = parent_ok and eval_cond(s[len('//#if '):].strip(), flags)
            stack.append([parent_ok, ok])
            active = ok
            out.append('')          # 占位，保持行号稳定
        elif s.startswith('//#elif '):
            if stack:
                parent_ok, taken = stack[-1]
                ok = parent_ok and (not taken) and eval_cond(s[len('//#elif '):].strip(), flags)
                stack[-1][1] = taken or ok
                active = ok
            out.append('')
        elif s.startswith('//#else'):
            if stack:
                parent_ok, taken = stack[-1]
                now = parent_ok and not taken
                stack[-1][1] = taken or now
                active = now
            out.append('')
        elif s.startswith('//#endif'):
            if stack:
                parent_ok, _ = stack.pop()
                active = parent_ok
            out.append('')
        else:
            out.append(line if active else '')
    # 清理空占位行（折叠连续空行，输出更好读）
    text = '\n'.join(out)
    text = re.sub(r'\n{3,}', '\n\n', text)
    return text


def apply_tokens(text, t):
    repl = {
        'GITHUB_REPO': GH_REPO,
        'MC': t['mc'],
        'BUILD_JAVA': str(25 if t['loader'] == 'fabric' else t['java']),
        'MC_RANGE': mc_range(t['mc']),
        'JAVA': str(t['java']),
        'JAVA_MIN': str(t['java']),
        'GRADLE': t['gradle'],
        'PACK_FORMAT': str(pack_format(t['mc'])),
        'PROJECT_NAME': 'unboundedheight-' + t['loader'] + '-' + t['mc'],
        'LOADER': t['loader'],
        'MDG_VERSION': '2.0.148',
        'LOOM_PLUGIN': t.get('loom', 'fabric-loom-remap'),
        'LOOM_VERSION': '1.18-SNAPSHOT',
        'LOADER_VERSION': '0.19.5',
        'FABRIC_API': t.get('api', ''),
        'NEO_VERSION': t.get('neo', ''),
        'FORGE_VERSION': t.get('forge', ''),
        'MIXIN_COMPAT': 'JAVA_17' if mc_code(t['mc']) <= 12004 else 'JAVA_21',
        'FORGE_MIN': LOADER_MIN['forge'],
        'NEO_MIN': '1',
    }
    for key, value in repl.items():
        text = text.replace('{{' + key + '}}', str(value))
    return text


def write_file(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(content)


BINARY_EXT = ('.png', '.jpg', '.jpeg', '.gif', '.ogg', '.jar', '.zip', '.ico')


def copy_tree(src, dst, flags, t):
    for base, dirs, files in os.walk(src):
        rel = os.path.relpath(base, src)
        target_dir = dst if rel == '.' else os.path.join(dst, rel)
        for name in files:
            src_path = os.path.join(base, name)
            target = os.path.join(target_dir, name)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            if name.lower().endswith(BINARY_EXT):
                shutil.copy2(src_path, target)
                continue
            content = open(src_path, encoding='utf-8').read()
            processed = preprocess(content, flags)
            if processed is None:
                continue
            processed = apply_tokens(processed, t)
            with open(target, 'w', encoding='utf-8', newline='\n') as fh:
                fh.write(processed)


def transform_config(text, loader, mc_code_value):
    """把 NeoConfig.java 转成 Forge 版 ForgeConfig.java。"""
    text = text.replace('package dev.zxin.unboundedheight.neoforge;', 'package dev.zxin.unboundedheight.forge;')
    text = text.replace('import net.neoforged.neoforge.common.ModConfigSpec;',
                        'import net.minecraftforge.common.ForgeConfigSpec;')
    text = text.replace('ModConfigSpec', 'ForgeConfigSpec')
    text = text.replace('class NeoConfig', 'class ForgeConfig')
    text = text.replace('private NeoConfig()', 'private ForgeConfig()')
    return text


def generate_one(t):
    flags = build_flags(t)
    branch = t['loader'] + '-' + t['mc']
    dest = os.path.join(OUT, branch)
    if os.path.exists(dest):
        shutil.rmtree(dest)
    os.makedirs(dest, exist_ok=True)

    # 1. 共用代码
    copy_tree(os.path.join(TPL, 'common'), os.path.join(dest, 'src/main/java'), flags, t)

    # 2. 加载器代码
    loader_dir = os.path.join(TPL, t['loader'])
    if os.path.isdir(loader_dir):
        copy_tree(loader_dir, os.path.join(dest, 'src/main/java'), flags, t)

    # 3. 资源
    res = os.path.join(dest, 'src/main/resources')
    copy_tree(os.path.join(TPL, 'resources', 'assets'), os.path.join(res, 'assets'), flags, t)
    write_file(os.path.join(res, 'pack.mcmeta'),
               apply_tokens(open(os.path.join(TPL, 'resources', 'pack.mcmeta'),
                                 encoding='utf-8').read(), t))

    if t['loader'] == 'fabric':
        write_file(os.path.join(res, 'fabric.mod.json'),
                   apply_tokens(open(os.path.join(TPL, 'resources', 'fabric.mod.json'),
                                     encoding='utf-8').read(), t))
        pass
    else:
        toml = 'mods.toml' if t['loader'] == 'forge' else 'neoforge.mods.toml'
        write_file(os.path.join(res, 'META-INF', toml),
                   apply_tokens(open(os.path.join(TPL, 'resources', toml), encoding='utf-8').read(), t))
        shutil.copy2(os.path.join(TPL, 'resources', 'icon.png'), os.path.join(res, 'icon.png'))

    # 3.5 Mixin 配置（三个加载器都要）
    mixin_json = preprocess(open(os.path.join(TPL, 'resources', 'unbounded_height.mixins.json'),
                                 encoding='utf-8').read(), flags)
    write_file(os.path.join(res, 'unbounded_height.mixins.json'), apply_tokens(mixin_json, t))

    # 4. 构建脚本
    bdir = os.path.join(TPL, 'build', t['loader'])
    copy_tree(bdir, dest, flags, t)
    settings_src = os.path.join(TPL, 'build', 'forge-neo' if t['loader'] != 'fabric' else 'fabric',
                                'settings.gradle')
    open(os.path.join(dest, 'settings.gradle'), 'w',
         encoding='utf-8', newline='\n').write(
        apply_tokens(open(settings_src, encoding='utf-8').read(), t))

    # 5. NeoForge 的 parchment 块 / Fabric 的 mappings 行 / 依赖前缀
    if t['loader'] == 'neoforge':
        bg = os.path.join(dest, 'build.gradle')
        text = open(bg, encoding='utf-8').read()
        if t.get('parchment'):
            block = ('    parchment {\n'
                     "        minecraftVersion = '%s'\n" % t['mc'] +
                     "        mappingsVersion = '%s'\n" % t['parchment'] +
                     '    }')
        else:
            block = ''
        text = text.replace('{{PARCHMENT_BLOCK}}', block)
        open(bg, 'w', encoding='utf-8', newline='\n').write(text)
    if t['loader'] == 'forge':
        bg = os.path.join(dest, 'build.gradle')
        text = open(bg, encoding='utf-8').read().replace('{{PARCHMENT_BLOCK}}\n', '')
        open(bg, 'w', encoding='utf-8', newline='\n').write(text)
    if t['loader'] == 'fabric':
        bg = os.path.join(dest, 'build.gradle')
        text = open(bg, encoding='utf-8').read()
        if mc_code(t['mc']) >= 26000:
            text = text.replace('{{MAPPINGS_LINE}}', '')
            text = text.replace('{{DEPENDENCY_PREFIX}}', 'implementation')
        else:
            text = text.replace('{{MAPPINGS_LINE}}', '\tmappings loom.officialMojangMappings()')
            text = text.replace('{{DEPENDENCY_PREFIX}}', 'modImplementation')
        open(bg, 'w', encoding='utf-8', newline='\n').write(text)

    # 7. gradle wrapper
    wdir = os.path.join(dest, 'gradle', 'wrapper')
    os.makedirs(wdir, exist_ok=True)
    open(os.path.join(wdir, 'gradle-wrapper.properties'), 'w', encoding='utf-8', newline='\n').write(
        apply_tokens(open(os.path.join(TPL, 'build', 'gradle-wrapper.properties'),
                          encoding='utf-8').read(), t))
    shutil.copy2(os.path.join(WRAP, 'gradle-wrapper.jar'), os.path.join(wdir, 'gradle-wrapper.jar'))
    for name in ('gradlew', 'gradlew.bat'):
        src = os.path.join(WRAP, name)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(dest, name))
    os.chmod(os.path.join(dest, 'gradlew'), 0o755)

    # 8. LICENSE / .gitignore / workflow
    shutil.copy2(os.path.join(ROOT, 'main', 'LICENSE'), os.path.join(dest, 'LICENSE'))
    shutil.copy2(os.path.join(ROOT, 'main', '.gitignore'), os.path.join(dest, '.gitignore'))
    wf = os.path.join(dest, '.github', 'workflows')
    os.makedirs(wf, exist_ok=True)
    open(os.path.join(wf, 'build.yml'), 'w', encoding='utf-8', newline='\n').write(
        apply_tokens(open(os.path.join(TPL, 'workflows', 'build.yml'), encoding='utf-8').read(), t))

    print('  ✔ %-20s java=%s gradle=%s' % (branch, t['java'], t['gradle']))
    return dest


def main():
    os.makedirs(OUT, exist_ok=True)
    only = sys.argv[1] if len(sys.argv) > 1 else None
    count = 0
    for t in TARGETS:
        branch = t['loader'] + '-' + t['mc']
        if only and branch != only:
            continue
        generate_one(t)
        count += 1
    print('\n共生成 %d 个分支 → %s' % (count, OUT))


if __name__ == '__main__':
    main()
