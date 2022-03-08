/*
 * Copyright (C) 2020 The Baremaps Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.baremaps.store.memory;

import java.nio.ByteBuffer;

/** A memory that stores segments off-heap using direct byte buffers. */
public class OffHeapMemory extends Memory {

  public OffHeapMemory() {
    this(1 << 20);
  }

  public OffHeapMemory(int segmentSize) {
    super(segmentSize);
  }

  @Override
  protected ByteBuffer allocate(int index, int size) {
    return ByteBuffer.allocateDirect(size);
  }
}