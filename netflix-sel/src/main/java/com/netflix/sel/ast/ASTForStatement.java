/* Generated By:JJTree: Do not edit this line. ASTForStatement.java Version 6.0 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=true,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=AST,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.netflix.sel.ast;

import com.netflix.sel.visitor.SelBaseNode;

public class ASTForStatement extends SelBaseNode {
  public ASTForStatement(int id) {
    super(id);
  }

  public ASTForStatement(SelParser p, int id) {
    super(p, id);
  }

  /** Accept the visitor. * */
  public Object jjtAccept(SelParserVisitor visitor, Object data) {

    return visitor.visit(this, data);
  }
}
/* JavaCC - OriginalChecksum=98333826a144d58bf82e8ea2e934277d (do not edit this line) */