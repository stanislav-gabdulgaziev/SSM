/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.smartdata.server.engine.cmdlet;

import org.apache.hadoop.hdfs.DFSInputStream;
import org.apache.hadoop.io.compress.snappy.SnappyDecompressor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.smartdata.hdfs.SmartDecompressorStream;
import org.smartdata.metastore.MetaStore;
import org.smartdata.model.CmdletState;
import org.smartdata.model.SmartFileCompressionInfo;
import org.smartdata.server.MiniSmartClusterHarness;
import org.smartdata.server.engine.CmdletManager;

import java.io.OutputStream;
import java.lang.reflect.Array;
import java.util.Random;

public class TestCompressionScheduler extends MiniSmartClusterHarness {
  public static final int DEFAULT_BLOCK_SIZE = 1024 * 64;
  
  @Override
  @Before
  public void setup() throws Exception {
    init(DEFAULT_BLOCK_SIZE);
  }

  @Test
  public void testScheduler() throws Exception {
    waitTillSSMExitSafeMode();

    MetaStore metaStore = ssm.getMetaStore();
    int bufferSize = 1024 * 128;
    String fileName = "/file1";
    byte[] bytes = TestCompressionScheduler.BytesGenerator.get(bufferSize);

    // Create HDFS file
    OutputStream outputStream = dfsClient.create(fileName, true);
    outputStream.write(bytes);
    outputStream.close();

    CmdletManager cmdletManager = ssm.getCmdletManager();
    long cmdId = cmdletManager.submitCmdlet(("compress -file /file1 -bufSize 131072"));

    while (true) {
      Thread.sleep(1000);
      CmdletState state = cmdletManager.getCmdletInfo(cmdId).getState();
      if (state == CmdletState.DONE) {
        
        //metastore  test
        SmartFileCompressionInfo compressionInfo = metaStore.getCompressionInfo(fileName);
        Assert.assertNull(compressionInfo);

        //data accuracy test
        byte[] input = new byte[bufferSize];
        DFSInputStream compressedInputStream = dfsClient.open(fileName);
        SmartDecompressorStream uncompressedStream = new SmartDecompressorStream(
          compressedInputStream, new SnappyDecompressor(bufferSize),
          bufferSize);
        int offset = 0;
        while (true) {
          int len = uncompressedStream.read(input, offset, bufferSize - offset);
          if (len <= 0) {
            break;
          }
          offset += len;
        }
        Assert.assertArrayEquals(
          "original array not equals compress/decompressed array", input, bytes
        );
        return;
      } else if (state == CmdletState.FAILED) {
        Assert.fail("Mover failed.");
      }
    }
  }

  static final class BytesGenerator {
    private static final byte[] CACHE = new byte[]{0x0, 0x1, 0x2, 0x3, 0x4,
      0x5, 0x6, 0x7, 0x8, 0x9, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF};
    private static final Random rnd = new Random(12345l);

    private BytesGenerator() {
    }

    public static byte[] get(int size) {
      byte[] array = (byte[]) Array.newInstance(byte.class, size);
      for (int i = 0; i < size; i++)
        array[i] = CACHE[rnd.nextInt(CACHE.length - 1)];
      return array;
    }
  }
}