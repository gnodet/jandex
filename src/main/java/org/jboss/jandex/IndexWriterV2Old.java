/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.jandex;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes a Jandex index file to a stream. The write process is somewhat more
 * expensive to allow for fast reads and a compact size. For more information on
 * the index content, see the documentation on {@link Indexer}.
 *
 * <p>
 * The IndexWriter operates on standard output streams, and also provides
 * suitable buffering.
 *
 * <p>
 * <b>Thread-Safety</b>
 * </p>
 * IndexWriter is not thread-safe and can not be shared between concurrent
 * threads.
 *
 * @see Indexer
 * @see Index
 * @author Jason T. Greene
 *
 */
final class IndexWriterV2Old extends IndexWriterImpl{
    static final byte MIN_VERSION = 6;
    static final byte MAX_VERSION = 6;

    // babelfish (no h)
    private static final int MAGIC = 0xBABE1F15;
    private static final byte NULL_TARGET_TAG = 0;
    private static final byte FIELD_TAG = 1;
    private static final byte METHOD_TAG = 2;
    private static final byte METHOD_PARAMATER_TAG = 3;
    private static final byte CLASS_TAG = 4;
    private static final byte EMPTY_TYPE_TAG = 5;
    private static final byte CLASS_EXTENDS_TYPE_TAG = 6;
    private static final byte TYPE_PARAMETER_TAG = 7;
    private static final byte TYPE_PARAMETER_BOUND_TAG = 8;
    private static final byte METHOD_PARAMETER_TYPE_TAG = 9;
    private static final byte THROWS_TYPE_TAG = 10;
    private static final int AVALUE_BYTE = 1;
    private static final int AVALUE_SHORT = 2;
    private static final int AVALUE_INT = 3;
    private static final int AVALUE_CHAR = 4;
    private static final int AVALUE_FLOAT = 5;
    private static final int AVALUE_DOUBLE = 6;
    private static final int AVALUE_LONG = 7;
    private static final int AVALUE_BOOLEAN = 8;
    private static final int AVALUE_STRING = 9;
    private static final int AVALUE_CLASS = 10;
    private static final int AVALUE_ENUM = 11;
    private static final int AVALUE_ARRAY = 12;
    private static final int AVALUE_NESTED = 13;
    private static final int HAS_ENCLOSING_METHOD = 1;
    private static final int NO_ENCLOSING_METHOD = 0;


    private final OutputStream out;

    private NameTable names;
    private TreeMap<DotName, Integer> nameTable;
    private IdentityHashMap<AnnotationInstance, Integer> annotationTable;

    /**
     * Constructs an IndexWriter using the specified stream
     *
     * @param out a stream to write an index to
     */
    IndexWriterV2Old(OutputStream out) {
        this.out = out;
    }


    /**
     * Writes the specified index to the associated output stream. This may be called multiple times in order
     * to write multiple indexes.
     *
     * @param index the index to write to the stream
     * @param version the index file version
     * @return the number of bytes written to the stream
     * @throws java.io.IOException if any i/o error occurs
     */
    int write(Index index, int version) throws IOException {

        if (version < MIN_VERSION || version > MAX_VERSION) {
            throw new UnsupportedVersion("Version: " + version);
        }

        PackedDataOutputStream stream = new PackedDataOutputStream(new BufferedOutputStream(out));
        stream.writeInt(MAGIC);
        stream.writeByte(version);

        buildTables(index);
        writeByteTable(stream);
        writeStringTable(stream);
        writeNameTable(stream);
        writeAnnotationTable(stream);
        writeTypeTable(stream);
        writeTypeListTable(stream);
        writeMethodTable(stream);
        writeFieldTable(stream);
        writeClasses(stream, index);
        stream.flush();
        return stream.size();
    }

    private void writeStringTable(PackedDataOutputStream stream) throws IOException {
        StrongInternPool<String> stringPool = names.stringPool();
        stream.writePackedU32(stringPool.size());
        Iterator<String> iterator = stringPool.iterator();
        while (iterator.hasNext()) {
            String string = iterator.next();
            stream.writeUTF(string);
        }
    }

    private void writeByteTable(PackedDataOutputStream stream) throws IOException {
        StrongInternPool<byte[]> bytePool = names.bytePool();
        stream.writePackedU32(bytePool.size());
        Iterator<byte[]> iterator = bytePool.iterator();
        while (iterator.hasNext()) {
            byte[] bytes = iterator.next();
            stream.writePackedU32(bytes.length);
            stream.write(bytes);
        }
    }

    private void writeTypeTable(PackedDataOutputStream stream) throws IOException {
        StrongInternPool<Type> typePool = names.typePool();
        stream.writePackedU32(typePool.size());
        Iterator<Type> iterator = typePool.iterator();
        while (iterator.hasNext()) {
            writeTypeEntry(stream, iterator.next());
        }
    }

    private void writeTypeListTable(PackedDataOutputStream stream) throws IOException {
        StrongInternPool<Type[]> typeListPool = names.typeListPool();
        stream.writePackedU32(typeListPool.size());
        Iterator<Type[]> iterator = typeListPool.iterator();
        while (iterator.hasNext()) {
            Type[] types = iterator.next();
            stream.writePackedU32(types.length);
            for (Type type : types) {
                stream.writePackedU32(positionOf(type));
            }
        }
    }

    private void writeMethodTable(PackedDataOutputStream stream) throws IOException {
        StrongInternPool<MethodInternal> methodPool = names.methodPool();
        stream.writePackedU32(methodPool.size());
        Iterator<MethodInternal> iterator = methodPool.iterator();
        while (iterator.hasNext()) {
            writeMethodEntry(stream, iterator.next());
        }
    }

    private void writeFieldTable(PackedDataOutputStream stream) throws IOException {
        StrongInternPool<FieldInternal> fieldPool = names.fieldPool();
        stream.writePackedU32(fieldPool.size());
        Iterator<FieldInternal> iterator = fieldPool.iterator();
        while (iterator.hasNext()) {
            writeFieldEntry(stream, iterator.next());
        }
    }

    private void writeFieldEntry(PackedDataOutputStream stream, FieldInternal field) throws IOException {
        stream.writePackedU32(positionOf(field.nameBytes()));
        stream.writePackedU32(field.flags());
        stream.writePackedU32(positionOf(field.type()));
    }

    private void writeMethodEntry(PackedDataOutputStream stream, MethodInternal method) throws IOException {
        stream.writePackedU32(positionOf(method.nameBytes()));
        stream.writePackedU32(method.flags());
        Type[] typeParameters = method.typeParameterArray();
        stream.writeByte(typeParameters.length);
        for (Type typeParameter : typeParameters) {
            stream.writePackedU32(positionOf(typeParameter));
        }
        Type receiverType = method.receiverTypeField();
        stream.writePackedU32(receiverType == null ? 0 : positionOf(receiverType));
        stream.writePackedU32(positionOf(method.returnType()));
        Type[] parameters = method.parameterArray();
        stream.writeByte(parameters.length);
        for (Type parameter : parameters) {
            stream.writePackedU32(positionOf(parameter));
        }
        Type[] exceptions = method.exceptionArray();
        stream.writePackedU32(exceptions.length);
        for (Type exception : exceptions) {
            stream.writePackedU32(positionOf(exception));
        }
        AnnotationInstance[] annotations = method.annotationArray();
        stream.writePackedU32(annotations.length);
        for (AnnotationInstance annotation : annotations) {
            stream.writePackedU32(positionOf(annotation));
        }
    }

    private void writeAnnotationTable(PackedDataOutputStream stream) throws IOException {
        int pos = 1;
        for (Entry<AnnotationInstance, Integer> entry : annotationTable.entrySet()) {
            entry.setValue(pos++);
            writeAnnotation(stream, entry.getKey());
        }
    }

    private void writeAnnotation(PackedDataOutputStream stream, AnnotationInstance instance) throws IOException {
        stream.writePackedU32(positionOf(instance.name()));
        AnnotationTarget target = instance.target();
        writeAnnotationTarget(stream, target);
        writeAnnotationValues(stream, instance.values());
    }

    private void writeAnnotationTarget(PackedDataOutputStream stream, AnnotationTarget target) throws IOException {
        if (target instanceof FieldInfo) {
            FieldInfo field = (FieldInfo) target;
            stream.writeByte(FIELD_TAG);
            stream.writePackedU32(positionOf(field.fieldInternal()));
        } else if (target instanceof MethodInfo) {
            MethodInfo method = (MethodInfo) target;
            stream.writeByte(METHOD_TAG);
            stream.writePackedU32(positionOf(method.methodInternal()));
        } else if (target instanceof MethodParameterInfo) {
            MethodParameterInfo param = (MethodParameterInfo) target;
            MethodInfo method = param.method();
            stream.writeByte(METHOD_PARAMATER_TAG);
            stream.writePackedU32(positionOf(method.methodInternal()));
            stream.writePackedU32(param.position());
        } else if (target instanceof ClassInfo) {
            stream.writeByte(CLASS_TAG);
        } else if (target instanceof TypeTarget) {
            writeTypeTarget(stream, (TypeTarget)target);
        } else if (target == null) {
            stream.writeByte(NULL_TARGET_TAG);
        } else {
            throw new IllegalStateException("Unknown target");
        }
    }

    private void writeTypeTarget(PackedDataOutputStream stream, TypeTarget typeTarget) throws IOException {
        switch (typeTarget.kind()) {
            case EMPTY: {
                writeTypeTargetFields(stream, EMPTY_TYPE_TAG, typeTarget);
                stream.writeByte(typeTarget.asEmpty().isReceiver() ? 1 : 0);
                break;
            }
            case CLASS_EXTENDS: {
                writeTypeTargetFields(stream, CLASS_EXTENDS_TYPE_TAG, typeTarget);
                stream.writePackedU32(typeTarget.asClassExtends().position());
                break;
            }
            case METHOD_PARAMETER: {
                writeTypeTargetFields(stream, METHOD_PARAMETER_TYPE_TAG, typeTarget);
                stream.writePackedU32(typeTarget.asMethodParameter().position());
                break;
            }
            case TYPE_PARAMETER: {
                writeTypeTargetFields(stream, TYPE_PARAMETER_TAG, typeTarget);
                stream.writePackedU32(typeTarget.asTypeParameter().position());
                break;
            }
            case TYPE_PARAMETER_BOUND: {
                writeTypeTargetFields(stream, TYPE_PARAMETER_BOUND_TAG, typeTarget);
                stream.writePackedU32(typeTarget.asTypeParameterBound().position());
                stream.writePackedU32(typeTarget.asTypeParameterBound().boundPosition());
                break;
            }
            case THROWS: {
                writeTypeTargetFields(stream, THROWS_TYPE_TAG, typeTarget);
                stream.writePackedU32(typeTarget.asThrows().position());
                break;
            }
        }
    }

    private void writeTypeTargetFields(PackedDataOutputStream stream, byte tag, TypeTarget target) throws IOException {
        stream.writeByte(tag);
        writeAnnotationTarget(stream, target.enclosingTarget());
        stream.writePackedU32(positionOf(target.target()));
    }

    private void writeNameTable(PackedDataOutputStream stream) throws IOException {
        stream.writePackedU32(nameTable.size());

        // Zero is reserved for null
        int pos = 1;
        for (Entry<DotName, Integer> entry : nameTable.entrySet()) {
            entry.setValue(pos++);
            DotName name = entry.getKey();
            assert name.isComponentized();

            int nameDepth = 0;
            for (DotName prefix = name.prefix(); prefix != null; prefix = prefix.prefix())
                nameDepth++;

            nameDepth = nameDepth << 1 | (name.isInner() ? 1 : 0);

            stream.writePackedU32(nameDepth);
            stream.writePackedU32(positionOf(name.local()));
        }
    }

    private int positionOf(String string) {
        int pos = names.positionOf(string);
        if (pos < 1) {
            throw new IllegalStateException("Intern tables incomplete");
        }

        return pos;
    }

    private int positionOf(Type type) {
        int pos = names.positionOf(type);
        if (pos < 1) {
            throw new IllegalStateException("Intern tables incomplete");
        }

        return pos;
    }

    private int positionOf(Type[] types) {
        int pos = names.positionOf(types);
        if (pos < 1) {
            throw new IllegalStateException("Intern tables incomplete");
        }
        return pos;
    }

    private int positionOf(byte[] bytes) {
        int pos = names.positionOf(bytes);
        if (pos < 1) {
            throw new IllegalStateException("Intern tables incomplete");
        }
        return pos;
    }

    private int positionOf(MethodInternal method) {
        int pos = names.positionOf(method);
        if (pos < 1) {
            throw new IllegalStateException("Intern tables incomplete");
        }
        return pos;
    }

    private int positionOf(FieldInternal field) {
        int pos = names.positionOf(field);
        if (pos < 1) {
            throw new IllegalStateException("Intern tables incomplete");
        }
        return pos;
    }

    private int positionOf(DotName className) {
        Integer i = nameTable.get(className);
        if (i == null)
            throw new IllegalStateException("Class not found in class table:" + className);

        return i.intValue();
    }

    private int positionOf(AnnotationInstance instance) {
        Integer i = annotationTable.get(instance);
        if (i == null)
            throw new IllegalStateException("Annotation not found in annotation table:" + instance.name());

        return i.intValue();
    }


    private void writeClasses(PackedDataOutputStream stream, Index index) throws IOException {
        Collection<ClassInfo> classes = index.getKnownClasses();
        stream.writePackedU32(classes.size());
        for (ClassInfo clazz: classes) {
            writeClassEntry(stream, clazz);
        }
    }

    private void writeClassEntry(PackedDataOutputStream stream, ClassInfo clazz) throws IOException {
        stream.writePackedU32(positionOf(clazz.name()));
        stream.writePackedU32(clazz.superClassType() == null ? 0 : positionOf(clazz.superClassType()));
        stream.writeShort(clazz.flags());

        Type[] typeParameters = clazz.typeParameterArray();
        stream.writePackedU32(typeParameters.length);
        for (Type typeParameter : typeParameters) {
            stream.writePackedU32(positionOf(typeParameter));
        }

        Type[] interfaceTypes = clazz.interfaceTypeArray();
        stream.writePackedU32(interfaceTypes.length);
        for (Type interfaceType : interfaceTypes) {
            stream.writePackedU32(positionOf(interfaceType));
        }

        DotName enclosingClass = clazz.enclosingClass();
        String simpleName = clazz.simpleName();

        stream.writePackedU32(enclosingClass == null ? 0 : positionOf(enclosingClass));
        stream.writePackedU32(simpleName == null ? 0 : positionOf(simpleName));

        ClassInfo.EnclosingMethodInfo enclosingMethod = clazz.enclosingMethod();
        if (enclosingMethod == null) {
            stream.writeByte(NO_ENCLOSING_METHOD);
        } else {
            stream.writeByte(HAS_ENCLOSING_METHOD);
            stream.writePackedU32(positionOf(enclosingMethod.name()));
            stream.writePackedU32(positionOf(enclosingMethod.enclosingClass()));
            stream.writePackedU32(positionOf(enclosingMethod.returnType()));
            stream.writePackedU32(positionOf(enclosingMethod.parametersArray()));
        }

        FieldInternal[] fields = clazz.fieldArray();
        stream.writePackedU32(fields.length);
        for (FieldInternal field : fields) {
            stream.writePackedU32(positionOf(field));
        }

        MethodInternal[] methods = clazz.methodArray();
        stream.writePackedU32(methods.length);
        for (MethodInternal method : methods) {
            stream.writePackedU32(positionOf(method));
        }

        Set<Entry<DotName, List<AnnotationInstance>>> entrySet = clazz.annotations().entrySet();
        stream.writePackedU32(entrySet.size());
        for (Entry<DotName, List<AnnotationInstance>> entry :  entrySet) {
            List<AnnotationInstance> value = entry.getValue();
            stream.writePackedU32(value.size());
            for (AnnotationInstance annotation : value) {
                stream.writePackedU32(positionOf(annotation));
            }
        }
    }

    private void writeAnnotationValues(PackedDataOutputStream stream, Collection<AnnotationValue> values) throws IOException {
        stream.writePackedU32(values.size());
        for (AnnotationValue value : values) {
            writeAnnotationValue(stream, value);
        }
    }

    private void writeAnnotationValue(PackedDataOutputStream stream, AnnotationValue value) throws IOException {
        stream.writePackedU32(positionOf(value.name()));
        if (value instanceof AnnotationValue.ByteValue) {
            stream.writeByte(AVALUE_BYTE);
            stream.writeByte(value.asByte() & 0xFF);
        } else if  (value instanceof AnnotationValue.ShortValue) {
            stream.writeByte(AVALUE_SHORT);
            stream.writePackedU32(value.asShort() & 0xFFFF);
        } else if (value instanceof AnnotationValue.IntegerValue) {
            stream.writeByte(AVALUE_INT);
            stream.writePackedU32(value.asInt());
        } else if (value instanceof AnnotationValue.CharacterValue) {
            stream.writeByte(AVALUE_CHAR);
            stream.writePackedU32(value.asChar());
        } else if (value instanceof AnnotationValue.FloatValue) {
            stream.writeByte(AVALUE_FLOAT);
            stream.writeFloat(value.asFloat());
        } else if (value instanceof AnnotationValue.DoubleValue) {
            stream.writeByte(AVALUE_DOUBLE);
            stream.writeDouble(value.asDouble());
        } else if (value instanceof AnnotationValue.LongValue) {
            stream.writeByte(AVALUE_LONG);
            stream.writeLong(value.asLong());
        } else if (value instanceof AnnotationValue.BooleanValue) {
            stream.writeByte(AVALUE_BOOLEAN);
            stream.writeBoolean(value.asBoolean());
        } else if (value instanceof AnnotationValue.StringValue) {
            stream.writeByte(AVALUE_STRING);
            stream.writePackedU32(positionOf(value.asString()));
        } else if (value instanceof AnnotationValue.ClassValue) {
            stream.writeByte(AVALUE_CLASS);
            stream.writePackedU32(positionOf(value.asClass()));
        } else if (value instanceof AnnotationValue.EnumValue) {
            stream.writeByte(AVALUE_ENUM);
            stream.writePackedU32(positionOf(value.asEnumType()));
            stream.writePackedU32(positionOf(value.asEnum()));
        } else if (value instanceof AnnotationValue.ArrayValue) {
            AnnotationValue[] array = value.asArray();
            int length = array.length;
            stream.writeByte(AVALUE_ARRAY);
            stream.writePackedU32(length);

            for (AnnotationValue anArray : array) {
                writeAnnotationValue(stream, anArray);
            }
        } else if (value instanceof AnnotationValue.NestedAnnotation) {
            AnnotationInstance instance = value.asNested();
            Collection<AnnotationValue> values = instance.values();

            stream.writeByte(AVALUE_NESTED);
            stream.writePackedU32(positionOf(instance.name()));
            writeAnnotationValues(stream, values);
        }
    }

    private void writeTypeEntry(PackedDataOutputStream stream, Type type) throws IOException {
        stream.writeByte(type.kind().ordinal());

        switch (type.kind()) {
            case CLASS:
                stream.writePackedU32(positionOf(type.name()));
                break;
            case ARRAY:
                ArrayType arrayType = type.asArrayType();
                stream.writePackedU32(arrayType.dimensions());
                stream.writePackedU32(positionOf(arrayType.component()));
                break;
            case PRIMITIVE:
                stream.writeByte(type.asPrimitiveType().primitive().ordinal());
                break;
            case VOID:
                break;
            case TYPE_VARIABLE:
                TypeVariable typeVariable = type.asTypeVariable();
                stream.writePackedU32(positionOf(typeVariable.identifier()));
                stream.writePackedU32(positionOf(typeVariable.boundArray()));
                break;
            case UNRESOLVED_TYPE_VARIABLE:
                stream.writePackedU32(positionOf(type.asUnresolvedTypeVariable().identifier()));
                break;
            case WILDCARD_TYPE:
                WildcardType wildcardType = type.asWildcardType();
                stream.writePackedU32(wildcardType.isExtends() ? 1 : 0);
                stream.writePackedU32(positionOf(wildcardType.bound()));
                break;
            case PARAMETERIZED_TYPE:
                ParameterizedType parameterizedType = type.asParameterizedType();
                Type owner = parameterizedType.owner();
                stream.writePackedU32(owner == null ? 0 : positionOf(owner));
                stream.writePackedU32(positionOf(parameterizedType.parameterArray()));
                break;
        }

        AnnotationInstance[] annotations = type.annotationArray();
        stream.writePackedU32(annotations.length);
        for (AnnotationInstance annotation : annotations) {
            stream.writePackedU32(positionOf(annotation));
        }
    }

    private void buildTables(Index index) {
        nameTable = new TreeMap<DotName, Integer>();
        annotationTable = new IdentityHashMap<AnnotationInstance, Integer>();
        names = new NameTable();

        // Build the stringPool for all strings
        for (ClassInfo clazz : index.getKnownClasses()) {
            addClass(clazz);
        }
    }

    private void addClass(ClassInfo clazz) {
        addClassName(clazz.name());
        if (clazz.superName() != null)
            addClassName(clazz.superName());

        addTypeList(clazz.typeParameterArray());
        addTypeList(clazz.interfaceTypeArray());
        addType(clazz.superClassType());

        // Inner class data
        DotName enclosingClass = clazz.enclosingClass();
        if (enclosingClass != null) {
            addClassName(enclosingClass);
        }
        String name = clazz.simpleName();
        if (name != null) {
            addString(name);
        }
        addEnclosingMethod(clazz.enclosingMethod());
        addMethodList(clazz.methodArray());
        addFieldList(clazz.fieldArray());

        for (Entry<DotName, List<AnnotationInstance>> entry :  clazz.annotations().entrySet()) {
            addClassName(entry.getKey());

            for (AnnotationInstance instance: entry.getValue()) {
                addAnnotation(instance);
            }
        }
    }

    private void addAnnotation(AnnotationInstance instance) {
        if (annotationTable.containsKey(instance)) {
            return;
        }

        addClassName(instance.name());
        annotationTable.put(instance, null);
        for (AnnotationValue value : instance.values()) {
            buildAValueEntries(value);
        }
    }

    private void addFieldList(FieldInternal[] fields) {
        for (FieldInternal field : fields) {
            deepIntern(field);
        }
    }

    private void deepIntern(FieldInternal field) {
        addType(field.type());
        names.intern(field);
        names.intern(field.nameBytes());
    }

    private void addMethodList(MethodInternal[] methods) {
        for (MethodInternal method : methods) {
            deepIntern(method);
        }
    }

    private void deepIntern(MethodInternal method) {
        addType(method.returnType());
        addType(method.receiverTypeField());
        addTypeList(method.typeParameterArray());
        addTypeList(method.parameterArray());
        addTypeList(method.exceptionArray());
        names.intern(method);
        names.intern(method.nameBytes());
    }

    private void addEnclosingMethod(ClassInfo.EnclosingMethodInfo enclosingMethod) {
        if (enclosingMethod == null) {
            return;
        }

        addString(enclosingMethod.name());
        addType(enclosingMethod.returnType());
        addTypeList(enclosingMethod.parametersArray());
        addClassName(enclosingMethod.enclosingClass());
    }

    private Type[] intern(Type[] types) {
        return names.intern(types);
    }

    private void addTypeList(Type[] types) {
        intern(types);

        for (Type type : types) {
            addType(type);
        }
    }

    private void addType(Type type) {
        intern(type);
        switch (type.kind()) {
            case CLASS:
                addClassName(type.asClassType().name());
                break;
            case ARRAY:
                intern(type.asArrayType().component());
                break;
            case TYPE_VARIABLE: {
                TypeVariable typeVariable = type.asTypeVariable();
                addString(typeVariable.identifier());
                addTypeList(typeVariable.boundArray());
                break;
            }
            case UNRESOLVED_TYPE_VARIABLE:
                addString(type.asUnresolvedTypeVariable().identifier());
                break;
            case WILDCARD_TYPE:
                addType(type.asWildcardType().bound());
                break;
            case PARAMETERIZED_TYPE:
                ParameterizedType parameterizedType = type.asParameterizedType();
                addType(parameterizedType.owner());
                addTypeList(parameterizedType.parameterArray());
                break;
            case PRIMITIVE:
            case VOID:
                break;
        }

        for (AnnotationInstance instance : type.annotationArray()) {
            addAnnotation(instance);
        }
    }

    private void buildAValueEntries(AnnotationValue value) {
        addString(value.name());

        if (value instanceof AnnotationValue.StringValue) {
            addString(value.asString());
        } else if (value instanceof AnnotationValue.ClassValue) {
            addClassName(value.asClass().name());
        } else if (value instanceof AnnotationValue.EnumValue) {
            addClassName(value.asEnumType());
            addString(value.asEnum());
        } else if (value instanceof AnnotationValue.ArrayValue) {
            for (AnnotationValue entry : value.asArray())
                buildAValueEntries(entry);
        } else if (value instanceof AnnotationValue.NestedAnnotation) {
            AnnotationInstance instance = value.asNested();
            addAnnotation(instance);
        }
    }

    private String addString(String name) {
        return names.intern(name);
    }

    private Type intern(Type type) {
        return names.intern(type);
    }

    private void addClassName(DotName name) {
        if (! nameTable.containsKey(name)) {
            addString(name.local());
            nameTable.put(name, null);
        }

        DotName prefix = name.prefix();
        if (prefix != null)
            addClassName(prefix);
    }
}
