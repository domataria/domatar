package com.domatar.pay;

import com.domatar.util.DomatarException;
import com.domatar.util.DomatarMsgClient;
import com.domatar.util.JsonMsg;
import com.domatar.util.Obj;
import com.domatar.util.ObjImpl;
import com.domatar.util.Rights;

public class PaymentProbeImpl extends ObjImpl
{
  public boolean allow = true;
  public Rights scripted = null;

  @Override
  public boolean hasRights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    return allow;
  }

  @Override
  public Rights rights(final JsonMsg inMsg, final Obj obj, final DomatarMsgClient msgClient)
      throws DomatarException
  {
    if (scripted != null)
      return scripted;
    return super.rights(inMsg, obj, msgClient);
  }
}
