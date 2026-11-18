"""Attach Collie's existing signing records without recompressing them."""

import struct
import sys
from zipfile import ZipFile

from apksigcopier import extract_v2_sig, is_meta, zip_data


def signing_directory(directory, entries):
    """Return raw central-directory records for the trailing v1 signature entries."""
    start = offset = 0
    for entry in entries:
        if directory[offset:offset + 4] != b"PK\x01\x02":
            raise ValueError("Invalid central-directory record")
        name, extra, comment = struct.unpack_from("<HHH", directory, offset + 28)
        offset += 46 + name + extra + comment
        if not is_meta(entry.filename):
            start = offset
    if offset != len(directory):
        raise ValueError("Unexpected central-directory layout")
    return directory[start:]


def copy_signing_entries(release, rebuilt, signatures, block_start):
    for entry in sorted(signatures, key=lambda item: item.header_offset):
        if release.tell() != entry.header_offset:
            raise ValueError("Unexpected data between signing entries")
        header = release.read(30)
        if header[:4] != b"PK\x03\x04":
            raise ValueError("Invalid signing entry header")
        name, extra = struct.unpack_from("<HH", header, 26)
        rebuilt.write(header)
        rebuilt.write(release.read(name + extra + entry.compress_size))
        if entry.flag_bits & 8:
            descriptor = release.read(12)
            if descriptor[:4] == b"PK\x07\x08":
                descriptor += release.read(4)
            rebuilt.write(descriptor)
    padding = block_start - release.tell()
    if not 0 <= padding < 4096 or release.read(padding) != bytes(padding):
        raise ValueError("Expected zero padding before the signing block")
    rebuilt.write(bytes(padding))


def reconstruct(published, aligned, output):
    signed = zip_data(published)
    unsigned = zip_data(aligned)
    signed_directory = signed.cd_and_eocd[:signed.eocd_offset - signed.cd_offset]
    unsigned_directory_size = unsigned.eocd_offset - unsigned.cd_offset
    block_start, signing_block = extract_v2_sig(published)

    with ZipFile(published) as apk:
        entries = apk.infolist()
    signatures = [entry for entry in entries if is_meta(entry.filename)]
    if signatures and (entries[-len(signatures):] != signatures or
                       min(entry.header_offset for entry in signatures) != unsigned.cd_offset or
                       any(entry.header_offset >= unsigned.cd_offset
                           for entry in entries[:-len(signatures)])):
        raise ValueError("V1 signing records must follow all unsigned entries")

    # Keep the app data and directory from the rebuild. The published APK supplies
    # only the appended signing records, alignment padding and APK signing block.
    signature_directory = signing_directory(signed_directory, entries)
    with open(aligned, "rb") as source, open(published, "rb") as release, open(output, "xb") as rebuilt:
        remaining = unsigned.cd_offset
        while remaining:
            chunk = source.read(min(remaining, 1 << 20))
            if not chunk:
                raise ValueError("Truncated unsigned APK")
            rebuilt.write(chunk)
            remaining -= len(chunk)
        release.seek(unsigned.cd_offset)
        copy_signing_entries(release, rebuilt, signatures, block_start)
        rebuilt.write(signing_block)
        directory_offset = rebuilt.tell()
        rebuilt.write(unsigned.cd_and_eocd[:unsigned_directory_size])
        rebuilt.write(signature_directory)
        end = bytearray(unsigned.cd_and_eocd[unsigned_directory_size:])
        disk_entries, total_entries = struct.unpack_from("<HH", end, 8)
        struct.pack_into("<HHII", end, 8, disk_entries + len(signatures), total_entries + len(signatures),
                         rebuilt.tell() - directory_offset, directory_offset)
        rebuilt.write(end)


if __name__ == "__main__":
    reconstruct(*sys.argv[1:])
