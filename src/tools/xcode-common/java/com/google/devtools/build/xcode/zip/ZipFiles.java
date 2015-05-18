// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.xcode.zip;

import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Utility code for reading information from zip files.
 */
public final class ZipFiles {
  /** Read a little-endian integer comprised of {@code count} bytes from the input channel. */
  private static int readBytes(int count, ReadableByteChannel input) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(count);
    if (input.read(buffer) != count) {
      throw new IOException("could not read expected number of bytes: " + count);
    }
    int result = 0;
    for (int i = count - 1; i >= 0; i--) {
      result <<= 8;
      result |= buffer.get(i) & 0xff;
    }
    return result;
  }

  /**
   * Returns the external file attributes of each entry as a mapping from the entry name to the
   * 32-bit value. As long as the attributes are generated by a Unix host, this includes the POSIX
   * file permissions in the upper two bytes. Entries not generated by a Unix host are not included
   * in the result.
   */
  public static Map<String, Integer> unixExternalFileAttributes(Path zipFile) throws IOException {
    // Field descriptions in comments were taken from this document:
    // http://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
    ImmutableMap.Builder<String, Integer> attributes = new ImmutableMap.Builder<>();
    try (SeekableByteChannel input = Files.newByteChannel(zipFile)) {
      // The data we care about is toward the end of the file, after the compressed data for each
      // file. We begin by looking for the start of the end of central directory record, which is
      // marked by the signature 0x06054b50
      //
      // This contains the centralDirectoryStartOffset value, which tells us where to seek to find
      // the first central directory entry. Each such entry is marked by the signature 0x02014b50
      // and appear in sequence, one entry for each file in the .zip.
      //
      // The central directory entry contains many values, including the file name, the external
      // file attributes, and the version made by value. If the version made by indicates a Unix
      // host (0x03??), we include the external file attributes in the returned map.

      long offset = input.size() - 4;
      while (offset >= 0) {
        input.position(offset);
        int signature = readBytes(4, input);
        if (signature == 0x06054b50) {
          break;
        } else if (signature == 0x06064b50) {
          throw new IOException("Zip64 format not supported: " + zipFile);
        }
        offset--;
      }
      if (offset < 0) {
        throw new IOException();
      }

      // Read end of central directory structure
      input.position(input.position()
          + 2 // number of this disk
          + 2 // number of the disk with the start of the central directory
      );
      int entryCount = readBytes(2, input);
      input.position(input.position()
          + 2 // total number of entries in the central directory
      );
      input.position(input.position()
          + 4 // size of the central directory
      );
      int centralDirectoryStartOffset = readBytes(4, input);
      if (0xffffffff == centralDirectoryStartOffset) {
        throw new IOException("Zip64 format not supported.");
      }

      input.position(centralDirectoryStartOffset);
      int entriesFound = 0;

      // Read each central directory entry
      while ((entriesFound < entryCount) && (readBytes(4, input) == 0x02014b50)) {
        int versionMadeBy = readBytes(2, input);
        input.position(input.position()
            + 2 // version needed to extract
            + 2 // general purpose bit flag
            + 2 // compression method
            + 2 // last mod file time
            + 2 // last mod file date
            + 4 // crc-32
            + 4 // compressed size
            + 4 // uncompressed size
        );
        int filenameLength = readBytes(2, input);
        int extraFieldLength = readBytes(2, input);
        int fileCommentLength = readBytes(2, input);
        input.position(input.position()
            + 2 // disk number start
            + 2 // internal file attributes
        );
        int externalFileAttributes = readBytes(4, input);
        input.position(input.position()
            + 4 // relative offset of local header
        );
        ByteBuffer filenameBuffer = ByteBuffer.allocate(filenameLength);
        if (filenameLength != input.read(filenameBuffer)) {
          throw new IOException(
              String.format(
                  "Could not read file name (length %d) in central directory record",
                  filenameLength));
        }
        input.position(input.position() + extraFieldLength + fileCommentLength);
        entriesFound++;
        if ((versionMadeBy >> 8) == 3) {
          // Zip made by a Unix host - the external file attributes are POSIX permissions.
          String filename = new String(filenameBuffer.array(), StandardCharsets.UTF_8);
          attributes.put(filename, externalFileAttributes);
        }
      }
      if (entriesFound != entryCount) {
        System.err.printf(
            "WARNING: Expected %d entries in central directory record in '%s', but found %d\n",
            entryCount, zipFile, entriesFound);
      }
    }
    return attributes.build();
  }

  private ZipFiles() {}
}