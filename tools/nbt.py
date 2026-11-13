"""最小 NBT 读取器（只读，够解析 MineColonies .blueprint）。"""
import gzip
import struct

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def u1(self):
        v = self.data[self.pos]
        self.pos += 1
        return v

    def i1(self):
        v = struct.unpack_from('>b', self.data, self.pos)[0]
        self.pos += 1
        return v

    def i2(self):
        v = struct.unpack_from('>h', self.data, self.pos)[0]
        self.pos += 2
        return v

    def i4(self):
        v = struct.unpack_from('>i', self.data, self.pos)[0]
        self.pos += 4
        return v

    def i8(self):
        v = struct.unpack_from('>q', self.data, self.pos)[0]
        self.pos += 8
        return v

    def f4(self):
        v = struct.unpack_from('>f', self.data, self.pos)[0]
        self.pos += 4
        return v

    def f8(self):
        v = struct.unpack_from('>d', self.data, self.pos)[0]
        self.pos += 8
        return v

    def string(self):
        n = struct.unpack_from('>H', self.data, self.pos)[0]
        self.pos += 2
        s = self.data[self.pos:self.pos + n].decode('utf-8', 'replace')
        self.pos += n
        return s

    def payload(self, tag):
        if tag == TAG_BYTE:
            return self.i1()
        if tag == TAG_SHORT:
            return self.i2()
        if tag == TAG_INT:
            return self.i4()
        if tag == TAG_LONG:
            return self.i8()
        if tag == TAG_FLOAT:
            return self.f4()
        if tag == TAG_DOUBLE:
            return self.f8()
        if tag == TAG_BYTE_ARRAY:
            n = self.i4()
            v = self.data[self.pos:self.pos + n]
            self.pos += n
            return v
        if tag == TAG_STRING:
            return self.string()
        if tag == TAG_LIST:
            item = self.u1()
            n = self.i4()
            return [self.payload(item) for _ in range(n)]
        if tag == TAG_COMPOUND:
            out = {}
            while True:
                t = self.u1()
                if t == TAG_END:
                    return out
                name = self.string()
                out[name] = self.payload(t)
        if tag == TAG_INT_ARRAY:
            n = self.i4()
            return [self.i4() for _ in range(n)]
        if tag == TAG_LONG_ARRAY:
            n = self.i4()
            return [self.i8() for _ in range(n)]
        raise ValueError('unknown tag %d at %d' % (tag, self.pos))


def load(path):
    with gzip.open(path, 'rb') as f:
        data = f.read()
    r = Reader(data)
    tag = r.u1()
    if tag != TAG_COMPOUND:
        raise ValueError('root is not compound')
    name = r.string()
    return name, r.payload(TAG_COMPOUND)
