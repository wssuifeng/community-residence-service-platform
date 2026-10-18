package com.community.residence.admin.community.service;
import java.util.*;
import java.lang.reflect.*;
import static org.mockito.Mockito.mock;
/** 原级联测试的Mapper替身保持不变，所有清理Service与协调Service使用真实实例。 */
public final class DeletionTestAssembly {
 private DeletionTestAssembly() { }
 public static CommunityDeletionService from(Object fixture) {
  var instances=new HashMap<Class<?>,Object>();
  try { for(var f:fixture.getClass().getDeclaredFields()) if(f.isAnnotationPresent(org.mockito.Mock.class)){f.setAccessible(true);instances.put(f.getType(),f.get(fixture));} }
  catch(IllegalAccessException e){throw new IllegalStateException(e);}
  return get(CommunityDeletionService.class,instances);
 }
 @SuppressWarnings("unchecked") public static <T>T get(Class<T> type,Map<Class<?>,Object> instances){
  if(instances.containsKey(type))return (T)instances.get(type);
  boolean real=type==CommunityDeletionService.class || type==com.community.residence.common.community.service.CommunityService.class || type.getSimpleName().endsWith("CleanupService");
  if(!real){var value=mock(type);instances.put(type,value);return value;}
  try {var c=Arrays.stream(type.getDeclaredConstructors()).max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();var args=Arrays.stream(c.getParameterTypes()).map(t->get(t,instances)).toArray();c.setAccessible(true);var value=(T)c.newInstance(args);instances.put(type,value);return value;}
  catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
 }
}
