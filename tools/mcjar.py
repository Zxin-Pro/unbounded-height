#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MC 官方 jar 字节码分析器（无需 JVM）。

用法:
  python3 mcjar.py <jar> --mappings <client_mappings.txt> <类名> [方法名 ...]
  python3 mcjar.py <jar> <类名>            # 无映射（26.x 已去混淆）

输出: 常量池(整数/字符串/引用) + 字段(含 ConstantValue) + 方法字节码反汇编
"""
import struct
import sys
import zipfile

# ---------------------------------------------------------------- 常量池解析
class CP:
    def __init__(self, data):
        self.data = data
        self.items = {}
        self.strings = {}
        self.ints = {}
        self.longs = {}
        self.floats = {}
        self.doubles = {}
        self.classname = {}
        self.nat = {}
        self.ref = {}
        self.methodref = {}
        self.fieldref = {}
        self._parse()

    def _parse(self):
        data = self.data
        idx = 8
        n = struct.unpack('>H', data[idx:idx + 2])[0]
        idx += 2
        i = 1
        while i < n:
            tag = data[idx]; idx += 1
            if tag == 1:
                ln = struct.unpack('>H', data[idx:idx + 2])[0]; idx += 2
                self.items[i] = ('utf8', data[idx:idx + ln].decode('utf-8', 'replace')); idx += ln
            elif tag == 3:
                v = struct.unpack('>i', data[idx:idx + 4])[0]; idx += 4
                self.items[i] = ('int', v); self.ints[i] = v
            elif tag == 4:
                v = struct.unpack('>f', data[idx:idx + 4])[0]; idx += 4
                self.items[i] = ('float', v); self.floats[i] = v
            elif tag == 5:
                v = struct.unpack('>q', data[idx:idx + 8])[0]; idx += 8
                self.items[i] = ('long', v); self.longs[i] = v; i += 1
            elif tag == 6:
                v = struct.unpack('>d', data[idx:idx + 8])[0]; idx += 8
                self.items[i] = ('double', v); self.doubles[i] = v; i += 1
            elif tag == 7:
                self.items[i] = ('class', struct.unpack('>H', data[idx:idx + 2])[0]); idx += 2
            elif tag == 8:
                si = struct.unpack('>H', data[idx:idx + 2])[0]; idx += 2
                self.items[i] = ('string', si); self.strings[i] = si
            elif tag == 9:
                c, nt = struct.unpack('>HH', data[idx:idx + 4]); idx += 4
                self.items[i] = ('fieldref', c, nt); self.fieldref[i] = (c, nt)
            elif tag == 10:
                c, nt = struct.unpack('>HH', data[idx:idx + 4]); idx += 4
                self.items[i] = ('methodref', c, nt); self.methodref[i] = (c, nt)
            elif tag == 11:
                c, nt = struct.unpack('>HH', data[idx:idx + 4]); idx += 4
                self.items[i] = ('imethodref', c, nt); self.methodref[i] = (c, nt)
            elif tag == 12:
                a, b = struct.unpack('>HH', data[idx:idx + 4]); idx += 4
                self.items[i] = ('nat', a, b); self.nat[i] = (a, b)
            elif tag == 15:
                idx += 3; self.items[i] = ('mh',)
            elif tag == 16:
                idx += 2; self.items[i] = ('mt',)
            elif tag in (17, 18):
                idx += 4; self.items[i] = ('dyn',)
            elif tag in (19, 20):
                idx += 2; self.items[i] = ('module',)
            else:
                raise Exception('cp tag %d @%d' % (tag, idx))
            i += 1
        self.next = idx

    def utf(self, i):
        it = self.items.get(i)
        return it[1] if it and it[0] == 'utf8' else '?'

    def cls(self, i):
        it = self.items.get(i)
        if it and it[0] == 'class':
            return self.utf(it[1])
        return '?'

    def refname(self, i):
        it = self.items.get(i)
        if it and it[0] in ('methodref', 'fieldref', 'imethodref'):
            c, nt = it[1], it[2]
            a, b = self.nat.get(nt, (0, 0))
            return '%s.%s%s' % (self.cls(c), self.utf(a), self.utf(b))
        if it and it[0] == 'class':
            return self.cls(i)
        if it and it[0] == 'string':
            return '"%s"' % self.utf(it[1])
        return str(it)


# ---------------------------------------------------------------- 属性/成员
def read_attrs(data, idx, cp, want=None):
    n = struct.unpack('>H', data[idx:idx + 2])[0]; idx += 2
    out = {}
    for _ in range(n):
        ni = struct.unpack('>H', data[idx:idx + 2])[0]; idx += 2
        ln = struct.unpack('>I', data[idx:idx + 4])[0]; idx += 4
        name = cp.utf(ni)
        body = data[idx:idx + ln]
        if want is None or name in want:
            out[name] = body
        idx += ln
    return out, idx


# ---------------------------------------------------------------- 反汇编
OPS = {
    0x00: ('nop', 0), 0x01: ('aconst_null', 0), 0x02: ('iconst_m1', 0), 0x03: ('iconst_0', 0),
    0x04: ('iconst_1', 0), 0x05: ('iconst_2', 0), 0x06: ('iconst_3', 0), 0x07: ('iconst_4', 0),
    0x08: ('iconst_5', 0), 0x09: ('lconst_0', 0), 0x0a: ('lconst_1', 0), 0x0b: ('fconst_0', 0),
    0x0c: ('fconst_1', 0), 0x0d: ('fconst_2', 0), 0x0e: ('dconst_0', 0), 0x0f: ('dconst_1', 0),
    0x10: ('bipush', 1), 0x11: ('sipush', 2), 0x12: ('ldc', 1), 0x13: ('ldc_w', 2),
    0x14: ('ldc2_w', 2), 0x15: ('iload', 1), 0x16: ('lload', 1), 0x17: ('fload', 1),
    0x18: ('dload', 1), 0x19: ('aload', 1), 0x1a: ('iload_0', 0), 0x1b: ('iload_1', 0),
    0x1c: ('iload_2', 0), 0x1d: ('iload_3', 0), 0x1e: ('lload_0', 0), 0x1f: ('lload_1', 0),
    0x20: ('lload_2', 0), 0x21: ('lload_3', 0), 0x22: ('fload_0', 0), 0x23: ('fload_1', 0),
    0x24: ('fload_2', 0), 0x25: ('fload_3', 0), 0x26: ('dload_0', 0), 0x27: ('dload_1', 0),
    0x28: ('dload_2', 0), 0x29: ('dload_3', 0), 0x2a: ('aload_0', 0), 0x2b: ('aload_1', 0),
    0x2c: ('aload_2', 0), 0x2d: ('aload_3', 0), 0x2e: ('iaload', 0), 0x2f: ('laload', 0),
    0x30: ('faload', 0), 0x31: ('daload', 0), 0x32: ('aaload', 0), 0x33: ('baload', 0),
    0x34: ('caload', 0), 0x35: ('saload', 0), 0x36: ('istore', 1), 0x37: ('lstore', 1),
    0x38: ('fstore', 1), 0x39: ('dstore', 1), 0x3a: ('astore', 1), 0x3b: ('istore_0', 0),
    0x3c: ('istore_1', 0), 0x3d: ('istore_2', 0), 0x3e: ('istore_3', 0), 0x3f: ('lstore_0', 0),
    0x40: ('lstore_1', 0), 0x41: ('lstore_2', 0), 0x42: ('lstore_3', 0), 0x43: ('fstore_0', 0),
    0x44: ('fstore_1', 0), 0x45: ('fstore_2', 0), 0x46: ('fstore_3', 0), 0x47: ('dstore_0', 0),
    0x48: ('dstore_1', 0), 0x49: ('dstore_2', 0), 0x4a: ('dstore_3', 0), 0x4b: ('astore_0', 0),
    0x4c: ('astore_1', 0), 0x4d: ('astore_2', 0), 0x4e: ('astore_3', 0), 0x4f: ('iastore', 0),
    0x50: ('lastore', 0), 0x51: ('fastore', 0), 0x52: ('dastore', 0), 0x53: ('aastore', 0),
    0x54: ('bastore', 0), 0x55: ('castore', 0), 0x56: ('sastore', 0), 0x57: ('pop', 0),
    0x58: ('pop2', 0), 0x59: ('dup', 0), 0x5a: ('dup_x1', 0), 0x5b: ('dup_x2', 0),
    0x5c: ('dup2', 0), 0x5d: ('dup2_x1', 0), 0x5e: ('dup2_x2', 0), 0x5f: ('swap', 0),
    0x60: ('iadd', 0), 0x61: ('ladd', 0), 0x62: ('fadd', 0), 0x63: ('dadd', 0), 0x64: ('isub', 0),
    0x65: ('lsub', 0), 0x66: ('fsub', 0), 0x67: ('dsub', 0), 0x68: ('imul', 0), 0x69: ('lmul', 0),
    0x6a: ('fmul', 0), 0x6b: ('dmul', 0), 0x6c: ('idiv', 0), 0x6d: ('ldiv', 0), 0x6e: ('fdiv', 0),
    0x6f: ('ddiv', 0), 0x70: ('irem', 0), 0x71: ('lrem', 0), 0x72: ('frem', 0), 0x73: ('drem', 0),
    0x74: ('ineg', 0), 0x75: ('lneg', 0), 0x76: ('fneg', 0), 0x77: ('dneg', 0), 0x78: ('ishl', 0),
    0x79: ('lshl', 0), 0x7a: ('ishr', 0), 0x7b: ('lshr', 0), 0x7c: ('iushr', 0), 0x7d: ('lushr', 0),
    0x7e: ('iand', 0), 0x7f: ('land', 0), 0x80: ('ior', 0), 0x81: ('lor', 0), 0x82: ('ixor', 0),
    0x83: ('lxor', 0), 0x84: ('iinc', 2), 0x85: ('i2l', 0), 0x86: ('i2f', 0), 0x87: ('i2d', 0),
    0x88: ('l2i', 0), 0x89: ('l2f', 0), 0x8a: ('l2d', 0), 0x8b: ('f2i', 0), 0x8c: ('f2l', 0),
    0x8d: ('f2d', 0), 0x8e: ('d2i', 0), 0x8f: ('d2l', 0), 0x90: ('d2f', 0), 0x91: ('i2b', 0),
    0x92: ('i2c', 0), 0x93: ('i2s', 0), 0x94: ('lcmp', 0), 0x95: ('fcmpl', 0), 0x96: ('fcmpg', 0),
    0x97: ('dcmpl', 0), 0x98: ('dcmpg', 0), 0x99: ('ifeq', 2), 0x9a: ('ifne', 2),
    0x9b: ('iflt', 2), 0x9c: ('ifge', 2), 0x9d: ('ifgt', 2), 0x9e: ('ifle', 2),
    0x9f: ('if_icmpeq', 2), 0xa0: ('if_icmpne', 2), 0xa1: ('if_icmplt', 2),
    0xa2: ('if_icmpge', 2), 0xa3: ('if_icmpgt', 2), 0xa4: ('if_icmple', 2),
    0xa5: ('if_acmpeq', 2), 0xa6: ('if_acmpne', 2), 0xa7: ('goto', 2), 0xa8: ('jsr', 2),
    0xa9: ('ret', 1), 0xaa: ('tableswitch', -1), 0xab: ('lookupswitch', -1),
    0xac: ('ireturn', 0), 0xad: ('lreturn', 0), 0xae: ('freturn', 0), 0xaf: ('dreturn', 0),
    0xb0: ('areturn', 0), 0xb1: ('return', 0), 0xb2: ('getstatic', 2), 0xb3: ('putstatic', 2),
    0xb4: ('getfield', 2), 0xb5: ('putfield', 2), 0xb6: ('invokevirtual', 2),
    0xb7: ('invokespecial', 2), 0xb8: ('invokestatic', 2), 0xb9: ('invokeinterface', 4),
    0xba: ('invokedynamic', 4), 0xbb: ('new', 2), 0xbc: ('newarray', 1), 0xbd: ('anewarray', 2),
    0xbe: ('arraylength', 0), 0xbf: ('athrow', 0), 0xc0: ('checkcast', 2),
    0xc1: ('instanceof', 2), 0xc2: ('monitorenter', 0), 0xc3: ('monitorexit', 0),
    0xc4: ('wide', -1), 0xc5: ('multianewarray', 3), 0xc6: ('ifnull', 2), 0xc7: ('ifnonnull', 2),
    0xc8: ('goto_w', 4), 0xc9: ('jsr_w', 4),
}
ATYPE = {4: 'boolean', 5: 'char', 6: 'float', 7: 'double', 8: 'byte', 9: 'short', 10: 'int', 11: 'long'}


def disasm(code, cp):
    out = []
    i = 0
    n = len(code)
    while i < n:
        pc = i
        op = code[i]; i += 1
        info = OPS.get(op)
        if not info:
            out.append('%5d: .byte 0x%02x' % (pc, op))
            continue
        name, ln = info
        if ln == -1:
            if name == 'tableswitch':
                pad = (4 - (i % 4)) % 4
                i += pad
                d, lo, hi = struct.unpack('>iii', code[i:i + 12]); i += 12 + 4 * (hi - lo + 1)
                out.append('%5d: tableswitch default=%d lo=%d hi=%d' % (pc, d, lo, hi))
            elif name == 'lookupswitch':
                pad = (4 - (i % 4)) % 4
                i += pad
                d, npairs = struct.unpack('>ii', code[i:i + 8]); i += 8 + 8 * npairs
                out.append('%5d: lookupswitch default=%d npairs=%d' % (pc, d, npairs))
            elif name == 'wide':
                op2 = code[i]; i += 1
                if op2 == 0x84:
                    a, b = struct.unpack('>Hh', code[i:i + 4]); i += 4
                    out.append('%5d: wide iinc %d %d' % (pc, a, b))
                else:
                    a = struct.unpack('>H', code[i:i + 2])[0]; i += 2
                    out.append('%5d: wide %s %d' % (pc, OPS.get(op2, ('?', 0))[0], a))
            continue
        args = code[i:i + ln]; i += ln
        if name == 'ldc':
            out.append('%5d: ldc %s' % (pc, cp.refname(args[0])))
        elif name == 'ldc_w':
            out.append('%5d: ldc_w %s' % (pc, cp.refname(struct.unpack('>H', args)[0])))
        elif name == 'ldc2_w':
            k = struct.unpack('>H', args)[0]
            it = cp.items.get(k)
            out.append('%5d: ldc2_w %s' % (pc, it[1] if it else '?'))
        elif name in ('bipush',):
            out.append('%5d: bipush %d' % (pc, struct.unpack('>b', args)[0]))
        elif name in ('sipush',):
            out.append('%5d: sipush %d' % (pc, struct.unpack('>h', args)[0]))
        elif name in ('getstatic', 'putstatic', 'getfield', 'putfield', 'invokevirtual',
                      'invokespecial', 'invokestatic', 'new', 'anewarray', 'checkcast', 'instanceof'):
            out.append('%5d: %-14s %s' % (pc, name, cp.refname(struct.unpack('>H', args)[0])))
        elif name == 'invokeinterface':
            out.append('%5d: invokeinterface %s' % (pc, cp.refname(struct.unpack('>H', args[:2])[0])))
        elif name == 'newarray':
            out.append('%5d: newarray %s' % (pc, ATYPE.get(args[0], '?')))
        elif name == 'multianewarray':
            out.append('%5d: multianewarray %s dims=%d' % (pc, cp.cls(struct.unpack('>H', args[:2])[0]), args[2]))
        elif name in ('iload', 'lload', 'fload', 'dload', 'aload', 'istore', 'lstore', 'fstore',
                      'dstore', 'astore', 'ret'):
            out.append('%5d: %s %d' % (pc, name, args[0]))
        elif name in ('ifeq', 'ifne', 'iflt', 'ifge', 'ifgt', 'ifle', 'if_icmpeq', 'if_icmpne',
                      'if_icmplt', 'if_icmpge', 'if_icmpgt', 'if_icmple', 'if_acmpeq', 'if_acmpne',
                      'goto', 'ifnull', 'ifnonnull'):
            out.append('%5d: %-14s -> %d' % (pc, name, pc + struct.unpack('>h', args)[0]))
        elif name == 'iinc':
            out.append('%5d: iinc %d %d' % (pc, args[0], struct.unpack('>b', args[1:2])[0]))
        elif ln == 0:
            out.append('%5d: %s' % (pc, name))
        else:
            out.append('%5d: %s %s' % (pc, name, args.hex()))
    return out


def main():
    argv = sys.argv[1:]
    jar = argv[0]
    mp = None
    only = []
    positional = []
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == '--mappings':
            mp = argv[i + 1]; i += 2
        elif a == '--only':
            only.append(argv[i + 1]); i += 2
        else:
            positional.append(a); i += 1
    targets = positional
    obf2name = {}
    members = {}
    if mp:
        cur = None
        for line in open(mp, encoding='utf-8'):
            if not line.strip():
                continue
            if not line.startswith(' '):
                # "net.minecraft.Foo -> abc:"
                parts = line.strip().rstrip(':').split(' -> ')
                if len(parts) == 2:
                    cur = parts[1]
                    obf2name[parts[1]] = parts[0]
            else:
                s = line.strip()
                if ' -> ' in s:
                    sig, obf = s.rsplit(' -> ', 1)
                    members.setdefault(cur, {})[obf] = sig
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        for t in targets:
            cls = t
            if mp:
                rev = {v: k for k, v in obf2name.items()}
                cls = rev.get(t, t)
            path = cls.replace('.', '/') + '.class'
            if path not in names:
                print('### %s -> %s : NOT FOUND' % (t, path)); continue
            data = z.read(path)
            cp = CP(data)
            mm = members.get(cls, {})
            print('#' * 78)
            print('### %s   (obf: %s)' % (t, cls))
            print('### fields:')
            idx = cp.next
            idx += 2  # access
            idx += 2  # this_class
            idx += 2  # super
            ifc = struct.unpack('>H', data[idx:idx + 2])[0]; idx += 2 + 2 * ifc
            nf = struct.unpack('>H', data[idx:idx + 2])[0]; idx += 2
            for _ in range(nf):
                acc, ni, di = struct.unpack('>HHH', data[idx:idx + 6]); idx += 6
                attrs, idx = read_attrs(data, idx, cp)
                cv = ''
                if 'ConstantValue' in attrs:
                    k = struct.unpack('>H', attrs['ConstantValue'])[0]
                    it = cp.items.get(k)
                    cv = ' = %r' % (it[1] if it else None)
                nm = cp.utf(ni)
                print('   %-42s %-24s %s%s' % (nm, cp.utf(di), mm.get(nm, ''), cv))
            nmeth = struct.unpack('>H', data[idx:idx + 2])[0]; idx += 2
            meths = []
            for _ in range(nmeth):
                acc, ni, di = struct.unpack('>HHH', data[idx:idx + 6]); idx += 6
                attrs, idx = read_attrs(data, idx, cp)
                meths.append((cp.utf(ni), cp.utf(di), attrs))
            print('### methods:')
            for nm, ds, attrs in meths:
                tag = mm.get(nm, '')
                print('   %-42s %-40s %s  code=%s' % (
                    nm, ds, tag, len(attrs.get('Code', b''))))
            # 反汇编指定方法（或全部）
            for nm, ds, attrs in meths:
                code = attrs.get('Code')
                if not code:
                    continue
                clen = struct.unpack('>I', code[0:4])[0]
                mlen = struct.unpack('>H', code[4:6])[0]
                cstart = 8
                bc = code[cstart:cstart + clen]
                if (not only) or any(v in ('ALL', nm) or (v and v in mm.get(nm, '')) for v in only):
                    print('--- %s %s (%s) ---' % (nm, ds, mm.get(nm, '')))
                    for ln in disasm(bc, cp):
                        print('   ' + ln)
            print()


def _want(obfname, tag, argv):
    """方法名过滤：命令行里 --only <名字或 mojmap 名> """
    if '--only' not in argv:
        return False
    vals = []
    for i, a in enumerate(argv):
        if a == '--only' and i + 1 < len(argv):
            vals.append(argv[i + 1])
    if not vals:
        return True
    for v in vals:
        if v == 'ALL' or v == obfname or v in tag:
            return True
    return False


if __name__ == '__main__':
    main()
