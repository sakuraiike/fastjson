package com.alibaba.fastjson.parser;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.DefaultJSONParser.ResolveTask;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;
import com.alibaba.fastjson.util.TypeUtils;

public class MapDeserializer implements ObjectDeserializer {
    public static MapDeserializer instance = new MapDeserializer();
    
    @SuppressWarnings("unchecked")
    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        if (type == JSONObject.class && parser.fieldTypeResolver == null) {
            return (T) parser.parseObject();
        }
        
        final JSONLexer lexer = parser.lexer;
        if (lexer.token == JSONToken.NULL) {
            lexer.nextToken(JSONToken.COMMA);
            return null;
        }

        Map<?, ?> map = createMap(type);

        ParseContext context = parser.context;

        try {
            parser.setContext(context, map, fieldName);
            return (T) deserialze(parser, type, fieldName, map);
        } finally {
            parser.setContext(context);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected Object deserialze(DefaultJSONParser parser, Type type, Object fieldName, Map map) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type keyType = parameterizedType.getActualTypeArguments()[0];
            Type valueType = parameterizedType.getActualTypeArguments()[1];

            if (String.class == keyType) {
                return parseMap(parser, (Map<String, Object>) map, valueType, fieldName);
            } else {
                return parseMap(parser, map, keyType, valueType, fieldName);
            }
        } else {
            return parser.parseObject(map, fieldName);
        }
    }
    
    @SuppressWarnings("rawtypes")
    public static Map parseMap(DefaultJSONParser parser, Map<String, Object> map, Type valueType, Object fieldName) {
        JSONLexer lexer = parser.lexer;

        if (lexer.token != JSONToken.LBRACE) {
            throw new JSONException("syntax error, expect {, actual " + lexer.token);
        }

        ParseContext context = parser.context;
        try {
            for (;;) {
                lexer.skipWhitespace();
                char ch = lexer.getCurrent();
                if ((lexer.features & Feature.AllowArbitraryCommas.mask) != 0) {
                    while (ch == ',') {
                        lexer.next();
                        lexer.skipWhitespace();
                        ch = lexer.getCurrent();
                    }
                }

                String key;
                if (ch == '"') {
                    key = lexer.scanSymbol(parser.symbolTable, '"');
                    lexer.skipWhitespace();
                    ch = lexer.getCurrent();
                    if (ch != ':') {
                        throw new JSONException("expect ':' at " + lexer.pos());
                    }
                } else if (ch == '}') {
                    lexer.next();
                    lexer.resetStringPosition();
                    lexer.nextToken(JSONToken.COMMA);
                    return map;
                } else if (ch == '\'') {
                    if ((lexer.features & Feature.AllowSingleQuotes.mask) == 0) {
                        throw new JSONException("syntax error");
                    }

                    key = lexer.scanSymbol(parser.symbolTable, '\'');
                    lexer.skipWhitespace();
                    ch = lexer.getCurrent();
                    if (ch != ':') {
                        throw new JSONException("expect ':' at " + lexer.pos());
                    }
                } else {
                    if ((lexer.features & Feature.AllowUnQuotedFieldNames.mask) == 0) {
                        throw new JSONException("syntax error");
                    }

                    key = lexer.scanSymbolUnQuoted(parser.symbolTable);
                    lexer.skipWhitespace();
                    ch = lexer.getCurrent();
                    if (ch != ':') {
                        throw new JSONException("expect ':' at " + lexer.pos() + ", actual " + ch);
                    }
                }

                lexer.next();
                lexer.skipWhitespace();
                ch = lexer.getCurrent();

                lexer.resetStringPosition();

                if (key == JSON.DEFAULT_TYPE_KEY && !lexer.isEnabled(Feature.DisableSpecialKeyDetect)) {
                    String typeName = lexer.scanSymbol(parser.symbolTable, '"');
                    Class<?> clazz = TypeUtils.loadClass(typeName, parser.config.defaultClassLoader);

                    if (clazz == map.getClass()) {
                        lexer.nextToken(JSONToken.COMMA);
                        if (lexer.token == JSONToken.RBRACE) {
                            lexer.nextToken(JSONToken.COMMA);
                            return map;
                        }
                        continue;
                    }

                    ObjectDeserializer deserializer = parser.config.getDeserializer(clazz);

                    lexer.nextToken(JSONToken.COMMA);

                    parser.resolveStatus = DefaultJSONParser.TypeNameRedirect;

                    if (context != null && !(fieldName instanceof Integer)) {
                        parser.popContext();
                    }

                    return (Map) deserializer.deserialze(parser, clazz, fieldName);
                }

                Object value;
                lexer.nextToken();

                parser.setContext(context);
                if (lexer.token == JSONToken.NULL) {
                    value = null;
                    lexer.nextToken();
                } else {
                    value = parser.parseObject(valueType, key);
                }

                map.put(key, value);
                parser.checkMapResolve(map, key);
                parser.setContext(context, value, key);                

                final int tok = lexer.token;
                if (tok == JSONToken.EOF || tok == JSONToken.RBRACKET) {
                    return map;
                }

                if (tok == JSONToken.RBRACE) {
                    lexer.nextToken();
                    return map;
                }
            }
        } finally {
            parser.setContext(context);
        }

    }
    
    public static Object parseMap(DefaultJSONParser parser, Map<Object, Object> map, Type keyType, Type valueType,
                                  Object fieldName) {
        JSONLexer lexer = parser.lexer;

        if (lexer.token != JSONToken.LBRACE && lexer.token != JSONToken.COMMA) {
            throw new JSONException("syntax error, expect {, actual " + lexer.tokenName());
        }

        ObjectDeserializer keyDeserializer = parser.config.getDeserializer(keyType);
        ObjectDeserializer valueDeserializer = parser.config.getDeserializer(valueType);
        lexer.nextToken();

        ParseContext context = parser.context;
        try {
            for (;;) {
                int token = lexer.token;
                if (token == JSONToken.RBRACE) {
                    lexer.nextToken(JSONToken.COMMA);
                    break;
                }

                if (lexer.token == JSONToken.LITERAL_STRING // 
                        && lexer.isRef() //
                        && !lexer.isEnabled(Feature.DisableSpecialKeyDetect)) {
                    Object object = null;

                    lexer.nextTokenWithChar(':');
                    if (lexer.token == JSONToken.LITERAL_STRING) {
                        String ref = lexer.stringVal();
                        if ("..".equals(ref)) {
                            ParseContext parentContext = context.parent;
                            object = parentContext.object;
                        } else if ("$".equals(ref)) {
                            ParseContext rootContext = context;
                            while (rootContext.parent != null) {
                                rootContext = rootContext.parent;
                            }

                            object = rootContext.object;
                        } else {
                            parser.addResolveTask(new ResolveTask(context, ref));
                            parser.resolveStatus = DefaultJSONParser.NeedToResolve;
                        }
                    } else {
                        throw new JSONException("illegal ref, " + JSONToken.name(lexer.token));
                    }

                    lexer.nextToken(JSONToken.RBRACE);
                    if (lexer.token != JSONToken.RBRACE) {
                        throw new JSONException("illegal ref");
                    }
                    lexer.nextToken(JSONToken.COMMA);

                    // parser.setContext(context, map, fieldName);
                    // parser.setContext(context);

                    return object;
                }

                if (map.size() == 0 //
                    && token == JSONToken.LITERAL_STRING //
                    && JSON.DEFAULT_TYPE_KEY.equals(lexer.stringVal()) //
                    && !lexer.isEnabled(Feature.DisableSpecialKeyDetect)) {
                    lexer.nextTokenWithChar(':');
                    lexer.nextToken(JSONToken.COMMA);
                    if (lexer.token == JSONToken.RBRACE) {
                        lexer.nextToken();
                        return map;
                    }
                    lexer.nextToken();
                }

                Object key = keyDeserializer.deserialze(parser, keyType, null);

                if (lexer.token != JSONToken.COLON) {
                    throw new JSONException("syntax error, expect :, actual " + lexer.token);
                }

                lexer.nextToken();

                Object value = valueDeserializer.deserialze(parser, valueType, key);
                parser.checkMapResolve(map, key);
                
                map.put(key, value);

                if (lexer.token == JSONToken.COMMA) {
                    lexer.nextToken();
                }
            }
        } finally {
            parser.setContext(context);
        }

        return map;
    }

    @SuppressWarnings({ "rawtypes" })
    protected Map<?, ?> createMap(Type type) {
        if (type == Properties.class) {
            return new Properties();
        }

        if (type == Hashtable.class) {
            return new Hashtable();
        }

        if (type == IdentityHashMap.class) {
            return new IdentityHashMap();
        }

        if (type == SortedMap.class || type == TreeMap.class) {
            return new TreeMap();
        }

        if (type == ConcurrentMap.class || type == ConcurrentHashMap.class) {
            return new ConcurrentHashMap();
        }
        
        if (type == Map.class || type == HashMap.class) {
            return new HashMap();
        }
        
        if (type == LinkedHashMap.class) {
            return new LinkedHashMap();
        }
        
        if (type == JSONObject.class) {
            return (Map<?, ?>) new JSONObject();
        }

        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;

            return createMap(parameterizedType.getRawType());
        }

        Class<?> clazz = (Class<?>) type;
        if (clazz.isInterface()) {
            throw new JSONException("unsupport type " + type);
        }
        
        try {
            return (Map<?, ?>) clazz.newInstance();
        } catch (Exception e) {
            throw new JSONException("unsupport type " + type, e);
        }
    }
}
