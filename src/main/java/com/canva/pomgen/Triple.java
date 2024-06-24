// Copyright 2023 Canva Inc. All Rights Reserved.

package com.canva.pomgen;

import java.util.List;

record Triple<A, B, C>(A a, B b, C c) {
  public List<Object> toList() {
    return List.of(a, b, c);
  }
}
