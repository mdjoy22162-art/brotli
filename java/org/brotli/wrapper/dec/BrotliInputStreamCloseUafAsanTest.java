/* Copyright 2026 Google Inc. All Rights Reserved.

   Distributed under MIT license.
   See file LICENSE for detail or copy at https://opensource.org/licenses/MIT
*/

package org.brotli.wrapper.dec;

import java.io.ByteArrayInputStream;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.brotli.integration.BrotliJniTestBase;
import org.brotli.wrapper.enc.Encoder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Reproduces the AddressSanitizer heap-use-after-free in {@link BrotliInputStream} when {@link
 * BrotliInputStream#close()} is called concurrently while {@link BrotliInputStream#read(byte[],
 * int, int)} is active inside JNI decompression.
 */
@RunWith(JUnit4.class)
public class BrotliInputStreamCloseUafAsanTest extends BrotliJniTestBase {

  @Test
  public void testConcurrentCloseDuringReadTriggersHeapUaf() throws Exception {
    byte[] payload = new byte[16 * 1024 * 1024];
    Random rng = new Random(0xB40711L);
    for (int i = 0; i < 65536; i++) {
      payload[i] = (byte) rng.nextInt(256);
    }
    final byte[] compressed =
        Encoder.compress(payload, new Encoder.Parameters().setQuality(1).setWindow(24));

    final byte[] outBuf = new byte[65536];

    for (int iter = 0; iter < 200; iter++) {
      final BrotliInputStream stream =
          new BrotliInputStream(new ByteArrayInputStream(compressed), 16384);
      final CountDownLatch startLatch = new CountDownLatch(1);
      final AtomicBoolean readStarted = new AtomicBoolean(false);

      Thread reader =
          new Thread(
              () -> {
                startLatch.countDown();
                readStarted.set(true);
                try {
                  stream.read(outBuf, 0, outBuf.length);
                } catch (Throwable ignored) {
                  // Ignore Java-level exceptions on iterations where close() completes before
                  // read().
                }
              });

      reader.start();
      startLatch.await();
      while (!readStarted.get()) {
        Thread.yield();
      }
      long spinNs = (iter % 40) * 10_000L;
      long deadline = System.nanoTime() + spinNs;
      while (System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      stream.close();
      reader.join();
    }
  }
}
