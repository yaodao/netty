/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util.internal;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

public abstract class TypeParameterMatcher {

    private static final TypeParameterMatcher NOOP = new TypeParameterMatcher() {
        @Override
        public boolean match(Object msg) {
            return true;
        }
    };

    // 取parameterType对应的Matcher对象
    public static TypeParameterMatcher get(final Class<?> parameterType) {
        // 可以理解为取了一个map
        final Map<Class<?>, TypeParameterMatcher> getCache =
                InternalThreadLocalMap.get().typeParameterMatcherGetCache();

        TypeParameterMatcher matcher = getCache.get(parameterType);
        if (matcher == null) {
            if (parameterType == Object.class) {
                matcher = NOOP;
            } else {
                // 非Object.class，对应的都是ReflectiveMatcher对象
                matcher = new ReflectiveMatcher(parameterType);
            }
            // 存入map
            getCache.put(parameterType, matcher);
        }

        return matcher;
    }

    /**
     * 查找object的泛型T对应的Matcher对象。并返回该Matcher对象。
     * （该泛型T可能是在object的类中声明的，也可能是在object的上层类中声明）
     *
     * @param object 一个对象
     * @param parametrizedSuperclass object对象的上层类clazz（上几层就不一定了）
     * @param typeParamName 泛型的字面值，该泛型是 [object.class, parametrizedSuperclass]中某个类声明的泛型
     * @return
     */
    public static TypeParameterMatcher find(
            final Object object, final Class<?> parametrizedSuperclass, final String typeParamName) {

        // 这个map存的是 某个类的Clazz， 该类使用的泛型"T"， 该泛型对应的Matcher对象
        // key是入参object.class， value的key是泛型字面值typeParamName， value是该泛型对应的Match对象
        final Map<Class<?>, Map<String, TypeParameterMatcher>> findCache =
                InternalThreadLocalMap.get().typeParameterMatcherFindCache();
        final Class<?> thisClass = object.getClass();

        // map的key是泛型字面值typeParamName， value是该泛型对应的Match对象
        Map<String, TypeParameterMatcher> map = findCache.get(thisClass);
        if (map == null) {
            map = new HashMap<String, TypeParameterMatcher>();
            findCache.put(thisClass, map);
        }

        TypeParameterMatcher matcher = map.get(typeParamName);
        if (matcher == null) {
            matcher = get(find0(object, parametrizedSuperclass, typeParamName));
            map.put(typeParamName, matcher);
        }

        return matcher;
    }

    /**
     * 找到泛型字面量"T" 的具体类型，并返回。（在 [object.class, parametrizedSuperclass]之间查找）
     * 是先从parametrizedSuperclass查找，再往下，一直到object.class。 一层层依次查找。（用到的反射函数在笔记都有说明）
     * 其中，泛型T是在[object.class, parametrizedSuperclass]的某个类中声明的
     *
     * @param object 一个对象
     * @param parametrizedSuperclass 一个带泛型的Class（对应的是入参object的上层类的Class）
     * @param typeParamName 泛型字面值，如 "T", "I" 等
     * @return
     */
    private static Class<?> find0(
            final Object object, Class<?> parametrizedSuperclass, String typeParamName) {

        final Class<?> thisClass = object.getClass();
        Class<?> currentClass = thisClass;
        for (;;) {
            if (currentClass.getSuperclass() == parametrizedSuperclass) {
                // 先定位泛型"T"的位置
                int typeParamIndex = -1;
                TypeVariable<?>[] typeParams = currentClass.getSuperclass().getTypeParameters();
                for (int i = 0; i < typeParams.length; i ++) {
                    if (typeParamName.equals(typeParams[i].getName())) {
                        typeParamIndex = i;
                        break;
                    }
                }

                if (typeParamIndex < 0) {
                    throw new IllegalStateException(
                            "unknown type parameter '" + typeParamName + "': " + parametrizedSuperclass);
                }

                // 再分析泛型"T"的具体类型

                Type genericSuperType = currentClass.getGenericSuperclass();
                if (!(genericSuperType instanceof ParameterizedType)) {
                    return Object.class;
                }
                // 从具体化的泛型中，找idx位置的具体化泛型
                Type[] actualTypeParams = ((ParameterizedType) genericSuperType).getActualTypeArguments();

                // 对idx位置的具体化泛型，继续分析它的具体类型。若能分析出来，则返回Class
                Type actualTypeParam = actualTypeParams[typeParamIndex];
                if (actualTypeParam instanceof ParameterizedType) {
                    actualTypeParam = ((ParameterizedType) actualTypeParam).getRawType();
                }
                if (actualTypeParam instanceof Class) {
                    return (Class<?>) actualTypeParam;
                }
                if (actualTypeParam instanceof GenericArrayType) {
                    Type componentType = ((GenericArrayType) actualTypeParam).getGenericComponentType();
                    if (componentType instanceof ParameterizedType) {
                        componentType = ((ParameterizedType) componentType).getRawType();
                    }
                    if (componentType instanceof Class) {
                        return Array.newInstance((Class<?>) componentType, 0).getClass();
                    }
                }
                // 若idx位置的具体泛型，还是泛型，则继续分析它的具体类型。
                if (actualTypeParam instanceof TypeVariable) {
                    // Resolved type parameter points to another type parameter.
                    // 已解析出的具体泛型，还是泛型，需要继续解析
                    TypeVariable<?> v = (TypeVariable<?>) actualTypeParam;
                    currentClass = thisClass;
                    if (!(v.getGenericDeclaration() instanceof Class)) {
                        return Object.class;
                    }

                    parametrizedSuperclass = (Class<?>) v.getGenericDeclaration();
                    typeParamName = v.getName();
                    if (parametrizedSuperclass.isAssignableFrom(thisClass)) {
                        continue;
                    } else {
                        return Object.class;
                    }
                }

                return fail(thisClass, typeParamName);
            }
            currentClass = currentClass.getSuperclass();
            if (currentClass == null) {
                return fail(thisClass, typeParamName);
            }
        }
    }
    // 若type类中 没有名字为typeParamName的泛型，则抛出异常。
    private static Class<?> fail(Class<?> type, String typeParamName) {
        throw new IllegalStateException(
                "cannot determine the type of the type parameter '" + typeParamName + "': " + type);
    }

    public abstract boolean match(Object msg);

    // 该Match用于判断参数msg是否为type类型
    private static final class ReflectiveMatcher extends TypeParameterMatcher {
        private final Class<?> type;

        ReflectiveMatcher(Class<?> type) {
            this.type = type;
        }

        @Override
        public boolean match(Object msg) {
            // msg是type类型或其子类型的对象，返回true
            // （等价于 msg instanceof type ， 下面这个写法不用加if判断，简化了）
            return type.isInstance(msg);
        }
    }

    TypeParameterMatcher() { }
}
