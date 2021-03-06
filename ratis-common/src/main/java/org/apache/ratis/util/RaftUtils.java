/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.util;

import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.ratis.conf.RaftProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class RaftUtils {
  public static final Logger LOG = LoggerFactory.getLogger(RaftUtils.class);

  // OSType detection
  public enum OSType {
    OS_TYPE_LINUX,
    OS_TYPE_WIN,
    OS_TYPE_SOLARIS,
    OS_TYPE_MAC,
    OS_TYPE_FREEBSD,
    OS_TYPE_OTHER
  }

  /**
   * Get the type of the operating system, as determined from parsing
   * the <code>os.name</code> property.
   */
  private static final OSType osType = getOSType();

  private static OSType getOSType() {
    String osName = System.getProperty("os.name");
    if (osName.startsWith("Windows")) {
      return OSType.OS_TYPE_WIN;
    } else if (osName.contains("SunOS") || osName.contains("Solaris")) {
      return OSType.OS_TYPE_SOLARIS;
    } else if (osName.contains("Mac")) {
      return OSType.OS_TYPE_MAC;
    } else if (osName.contains("FreeBSD")) {
      return OSType.OS_TYPE_FREEBSD;
    } else if (osName.startsWith("Linux")) {
      return OSType.OS_TYPE_LINUX;
    } else {
      // Some other form of Unix
      return OSType.OS_TYPE_OTHER;
    }
  }

  // Helper static vars for each platform
  public static final boolean WINDOWS = (osType == OSType.OS_TYPE_WIN);
  public static final boolean SOLARIS = (osType == OSType.OS_TYPE_SOLARIS);
  public static final boolean MAC     = (osType == OSType.OS_TYPE_MAC);
  public static final boolean FREEBSD = (osType == OSType.OS_TYPE_FREEBSD);
  public static final boolean LINUX   = (osType == OSType.OS_TYPE_LINUX);
  public static final boolean OTHER   = (osType == OSType.OS_TYPE_OTHER);

  public static final boolean PPC_64
      = System.getProperties().getProperty("os.arch").contains("ppc64");

  public static final Class<?>[] EMPTY_CLASSES = {};
  /**
   * Cache of constructors for each class. Pins the classes so they
   * can't be garbage collected until ReflectionUtils can be collected.
   */
  private static final Map<List<Class<?>>, Constructor<?>> CONSTRUCTOR_CACHE
      = new ConcurrentHashMap<>();

  public static InterruptedIOException toInterruptedIOException(
      String message, InterruptedException e) {
    final InterruptedIOException iioe = new InterruptedIOException(message);
    iioe.initCause(e);
    return iioe;
  }

  public static IOException asIOException(Throwable t) {
    return t instanceof IOException? (IOException)t : new IOException(t);
  }

  public static IOException toIOException(ExecutionException e) {
    final Throwable cause = e.getCause();
    return cause != null? asIOException(cause): new IOException(e);
  }

  /** Is the given object an instance of one of the given classes? */
  public static boolean isInstance(Object obj, Class<?>... classes) {
    for(Class<?> c : classes) {
      if (c.isInstance(obj)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Create an object for the given class using its default constructor.
   */
  public static <T> T newInstance(Class<T> clazz) {
    return newInstance(clazz, EMPTY_CLASSES);
  }

  /**
   * Create an object for the given class using the specified constructor.
   *
   * @param clazz class of which an object is created
   * @param argClasses argument classes of the constructor
   * @param args actual arguments to be passed to the constructor
   * @param <T> class type of clazz
   * @return a new object
   */
  public static <T> T newInstance(Class<T> clazz, Class<?>[] argClasses, Object... args) {
    Objects.requireNonNull(clazz, "clazz == null");
    try {
      final List<Class<?>> key = new ArrayList<>();
      key.add(clazz);
      key.addAll(Arrays.asList(argClasses));

      @SuppressWarnings("unchecked")
      Constructor<T> ctor = (Constructor<T>) CONSTRUCTOR_CACHE.get(key);
      if (ctor == null) {
        ctor = clazz.getDeclaredConstructor(argClasses);
        ctor.setAccessible(true);
        CONSTRUCTOR_CACHE.put(key, ctor);
      }
      return ctor.newInstance(args);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static <BASE> Class<? extends BASE> getClass(
      String subClassName, RaftProperties properties, Class<BASE> base) {
    try {
      return properties.getClassByName(subClassName).asSubclass(base);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Failed to get class "
          + subClassName + " as a subclass of " + base, e);
    }
  }

  /**
   * Create a memoized supplier which gets a value by invoking the initializer once
   * and then keeps returning the same value as its supplied results.
   *
   * @param initializer to supply at most one non-null value.
   * @param <T> The supplier result type.
   * @return a memoized supplier which is thread-safe.
   */
  public static <T> Supplier<T> memoize(Supplier<T> initializer) {
    Objects.requireNonNull(initializer, "initializer == null");
    return new Supplier<T>() {
      private volatile T value = null;

      @Override
      public T get() {
        T v = value;
        if (v == null) {
          synchronized (this) {
            v = value;
            if (v == null) {
              v = value = Objects.requireNonNull(initializer.get(),
                  "initializer.get() returns null");
            }
          }
        }
        return v;
      }
    };
  }

  public static void setLogLevel(Logger logger, Level level) {
    LogManager.getLogger(logger.getName()).setLevel(level);
  }


  public static void readFully(InputStream in, int buffSize) throws IOException {
    final byte buf[] = new byte[buffSize];
    for(int bytesRead = in.read(buf); bytesRead >= 0; ) {
      bytesRead = in.read(buf);
    }
  }

  /**
   * Reads len bytes in a loop.
   *
   * @param in InputStream to read from
   * @param buf The buffer to fill
   * @param off offset from the buffer
   * @param len the length of bytes to read
   * @throws IOException if it could not read requested number of bytes
   * for any reason (including EOF)
   */
  public static void readFully(InputStream in, byte[] buf, int off, int len)
      throws IOException {
    for(int toRead = len; toRead > 0; ) {
      final int ret = in.read(buf, off, toRead);
      if (ret < 0) {
        throw new IOException( "Premature EOF from inputStream");
      }
      toRead -= ret;
      off += ret;
    }
  }

  /**
   * Write a ByteBuffer to a FileChannel at a given offset,
   * handling short writes.
   *
   * @param fc               The FileChannel to write to
   * @param buf              The input buffer
   * @param offset           The offset in the file to start writing at
   * @throws IOException     On I/O error
   */
  public static void writeFully(FileChannel fc, ByteBuffer buf, long offset)
      throws IOException {
    do {
      offset += fc.write(buf, offset);
    } while (buf.remaining() > 0);
  }

  /**
   * Similar to readFully(). Skips bytes in a loop.
   * @param in The InputStream to skip bytes from
   * @param len number of bytes to skip.
   * @throws IOException if it could not skip requested number of bytes
   * for any reason (including EOF)
   */
  public static void skipFully(InputStream in, long len) throws IOException {
    long amt = len;
    while (amt > 0) {
      long ret = in.skip(amt);
      if (ret == 0) {
        // skip may return 0 even if we're not at EOF.  Luckily, we can
        // use the read() method to figure out if we're at the end.
        int b = in.read();
        if (b == -1) {
          throw new EOFException( "Premature EOF from inputStream after " +
              "skipping " + (len - amt) + " byte(s).");
        }
        ret = 1;
      }
      amt -= ret;
    }
  }

  /**
   * Close the Closeable objects and <b>ignore</b> any {@link Throwable} or
   * null pointers. Must only be used for cleanup in exception handlers.
   *
   * @param log the log to record problems to at debug level. Can be null.
   * @param closeables the objects to close
   */
  public static void cleanup(Logger log, Closeable... closeables) {
    for (Closeable c : closeables) {
      if (c != null) {
        try {
          c.close();
        } catch(Throwable e) {
          if (log != null && log.isDebugEnabled()) {
            log.debug("Exception in closing " + c, e);
          }
        }
      }
    }
  }

  /**
   *  @return the next element in the iteration right after the given element;
   *          if the given element is not in the iteration, return the first one
   */
  public static <T> T next(final T given, final Iterable<T> iteration) {
    Objects.requireNonNull(given, "given == null");
    final Iterator<T> i = Objects.requireNonNull(iteration, "iteration == null").iterator();
    assertTrue(i.hasNext(), "iteration is empty.");

    final T first = i.next();
    for(T current = first; i.hasNext(); ) {
      final T next = i.next();
      if (given.equals(current)) {
        return next;
      }
      current = next;
    }
    return first;
  }

  public static <INPUT, OUTPUT> Iterable<OUTPUT> as(
      Iterable<INPUT> iteration, Function<INPUT, OUTPUT> converter) {
    return () -> new Iterator<OUTPUT>() {
      final Iterator<INPUT> i = iteration.iterator();
      @Override
      public boolean hasNext() {
        return i.hasNext();
      }

      @Override
      public OUTPUT next() {
        return converter.apply(i.next());
      }
    };
  }

  /**
   * Assert if the given value is true.
   * @param value the value to be asserted.
   * @throws IllegalStateException if the given value is false.
   */
  public static void assertTrue(boolean value) {
    if (!value) {
      throw new IllegalStateException();
    }
  }

  /**
   * Assert if the given value is true.
   * @param value the value to be asserted.
   * @param message The exception message.
   * @throws IllegalStateException with the given message if the given value is false.
   */
  public static void assertTrue(boolean value, Object message) {
    if (!value) {
      throw new IllegalStateException(String.valueOf(message));
    }
  }

  /**
   * Assert if the given value is true.
   * @param value the value to be asserted.
   * @param format exception message format.
   * @param args exception message arguments.
   * @throws IllegalStateException if the given value is false.
   * The exception message is constructed by {@link String#format(String, Object...)}
   * with the given format and arguments.
   */
  public static void assertTrue(boolean value, String format, Object... args) {
    if (!value) {
      throw new IllegalStateException(String.format(format, args));
    }
  }

  /**
   * Assert if the given value is true.
   * @param value the value to be asserted.
   * @param message The exception message supplier.
   * @throws IllegalStateException with the given message if the given value is false.
   */
  public static void assertTrue(boolean value, Supplier<Object> message) {
    if (!value) {
      throw new IllegalStateException(String.valueOf(message.get()));
    }
  }

  public static Exception instantiateException(Class<? extends Exception> cls,
      String message, Exception from) throws Exception {
    Constructor<? extends Exception> cn = cls.getConstructor(String.class);
    cn.setAccessible(true);
    Exception ex = cn.newInstance(message);
    if (from != null) {
      ex.initCause(from);
    }
    return ex;
  }
}
