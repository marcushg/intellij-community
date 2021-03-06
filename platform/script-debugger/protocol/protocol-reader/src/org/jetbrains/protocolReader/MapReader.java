package org.jetbrains.protocolReader;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class MapReader extends ValueReader {
  private final ValueReader componentParser;

  MapReader(@Nullable ValueReader componentParser) {
    super();

    if (componentParser == null || componentParser instanceof ObjectValueReader) {
      this.componentParser = componentParser;
    }
    else {
      // if primitive (String), we don't need to use factory to read value
      this.componentParser = null;
    }
  }

  @Override
  public void appendFinishedValueTypeName(@NotNull TextOutput out) {
    out.append("java.util.Map");
    if (componentParser != null) {
      out.append('<');
      out.append("String, ");
      componentParser.appendFinishedValueTypeName(out);
      out.append('>');
    }
  }

  @Override
  void writeReadCode(ClassScope scope, boolean subtyping, String fieldName, @NotNull TextOutput out) {
    beginReadCall("Map", subtyping, out);
    if (componentParser == null) {
      out.comma().append("null");
    }
    else {
      ((ObjectValueReader)componentParser).writeFactoryArgument(scope, out);
    }
    out.append(')');
  }
}
