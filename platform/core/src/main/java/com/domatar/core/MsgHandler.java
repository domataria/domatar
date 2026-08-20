package com.domatar.core;

public class MsgHandler
{/*
  public String handleMsg(String msg, String srcDomain, String contextPath, String contextRealPath) throws DomatarException
  {
    return handleMsg(new JsonMsg(msg), srcDomain, contextPath, contextRealPath);
  }
  
  public String handleMsg(JsonMsg jMsg, String srcDomain, String contextPath, String contextRealPath) throws DomatarException
  {
    String retMsg;
    
    try
    {
      DomId dstDomId = jMsg.getDstId();
      
      String implName = getImplName(dstDomId);
      
      if (implName == null)
        retMsg = "{ \"Error\" : \"ImplNotFound\" }";
      else
      {
        Class<?> implClass = Class.forName(implName);
          
        DomatarInterface impl = (DomatarInterface)implClass.newInstance();
  
        if (impl == null)
          retMsg = "{ \"Error\" : \"ImplClassNotFound\" }";
        else
        {
          DomId srcDomId = jMsg.getSrcId();
          Context context = jMsg.getContext();
          
          DomatarMsgClient msgClient = new HttpClient(srcDomId,
                                                      srcDomain,
                                                      context, 
                                                      contextPath,
                                                      contextRealPath);
          
          retMsg = impl.handleMsg(jMsg.toString(), null, contextPath, contextRealPath, msgClient); 
        }
      }
    }
    catch (Exception e)
    {
      throw new DomatarException(e);
    }
    
    return retMsg;
  }
  
  private String getImplName(DomId domId)
  {
    return null;
  }*/
}
