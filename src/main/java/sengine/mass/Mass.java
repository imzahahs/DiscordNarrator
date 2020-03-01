
package sengine.mass;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.TimeZone;
import java.util.TreeMap;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.IntMap;

import sengine.mass.io.Input;
import sengine.mass.io.Output;
import sengine.mass.serializers.CollectionSerializer;
import sengine.mass.serializers.FailedSerializer;
import sengine.mass.serializers.FieldSerializer;
import sengine.mass.serializers.MapSerializer;
import sengine.mass.serializers.DefaultArraySerializers.BooleanArraySerializer;
import sengine.mass.serializers.DefaultArraySerializers.ByteArraySerializer;
import sengine.mass.serializers.DefaultArraySerializers.CharArraySerializer;
import sengine.mass.serializers.DefaultArraySerializers.DoubleArraySerializer;
import sengine.mass.serializers.DefaultArraySerializers.FloatArraySerializer;
import sengine.mass.serializers.DefaultArraySerializers.IntArraySerializer;
import sengine.mass.serializers.DefaultArraySerializers.LongArraySerializer;
import sengine.mass.serializers.DefaultArraySerializers.ObjectArraySerializer;
import sengine.mass.serializers.DefaultArraySerializers.ShortArraySerializer;
import sengine.mass.serializers.DefaultArraySerializers.StringArraySerializer;
import sengine.mass.serializers.DefaultSerializers.BigDecimalSerializer;
import sengine.mass.serializers.DefaultSerializers.BigIntegerSerializer;
import sengine.mass.serializers.DefaultSerializers.BooleanSerializer;
import sengine.mass.serializers.DefaultSerializers.ByteSerializer;
import sengine.mass.serializers.DefaultSerializers.CalendarSerializer;
import sengine.mass.serializers.DefaultSerializers.CharSerializer;
import sengine.mass.serializers.DefaultSerializers.ClassSerializer;
import sengine.mass.serializers.DefaultSerializers.CollectionsEmptyListSerializer;
import sengine.mass.serializers.DefaultSerializers.CollectionsEmptyMapSerializer;
import sengine.mass.serializers.DefaultSerializers.CollectionsEmptySetSerializer;
import sengine.mass.serializers.DefaultSerializers.CollectionsSingletonListSerializer;
import sengine.mass.serializers.DefaultSerializers.CollectionsSingletonMapSerializer;
import sengine.mass.serializers.DefaultSerializers.CollectionsSingletonSetSerializer;
import sengine.mass.serializers.DefaultSerializers.CurrencySerializer;
import sengine.mass.serializers.DefaultSerializers.DateSerializer;
import sengine.mass.serializers.DefaultSerializers.DoubleSerializer;
import sengine.mass.serializers.DefaultSerializers.EnumSerializer;
import sengine.mass.serializers.DefaultSerializers.EnumSetSerializer;
import sengine.mass.serializers.DefaultSerializers.FloatSerializer;
import sengine.mass.serializers.DefaultSerializers.IntSerializer;
import sengine.mass.serializers.DefaultSerializers.LongSerializer;
import sengine.mass.serializers.DefaultSerializers.ShortSerializer;
import sengine.mass.serializers.DefaultSerializers.StringBufferSerializer;
import sengine.mass.serializers.DefaultSerializers.StringBuilderSerializer;
import sengine.mass.serializers.DefaultSerializers.StringSerializer;
import sengine.mass.serializers.DefaultSerializers.TimeZoneSerializer;
import sengine.mass.serializers.DefaultSerializers.TreeMapSerializer;
import sengine.mass.serializers.MassSerializableSerializer;
import sengine.mass.util.IdentityObjectIntMap;

@SuppressWarnings({ "unchecked", "rawtypes" })
public class Mass {

    public static Charset charset = Charset.forName("UTF-8");

    // Hints
	public static float minCompressionRatio = 1.2f;		// 1.2f


	private static final ThreadLocal<Deflater> deflaterLocal = new ThreadLocal<Deflater>() {
		@Override
		protected Deflater initialValue() {
			return new Deflater(Deflater.BEST_COMPRESSION);
		}
	};
	private static final ThreadLocal<Inflater> inflaterLocal = new ThreadLocal<Inflater>() {
		@Override
		protected Inflater initialValue() {
			return new Inflater();
		}
	};

	private static final ThreadLocal<SecureRandom> secureRandomLocal = new ThreadLocal<SecureRandom>() {
        @Override
        protected SecureRandom initialValue() {
            return new SecureRandom();
        }
    };

    private static final int CRYPTO_IV_SIZE = 512;
    private static final int CRYPTO_IV_SIZE_MASK = 0x1FF;
    private static final int CRYPTO_KEY_SIZE = 16;
    private static final int CRYPTO_KEY_SIZE_MASK = 0xF;

    private static final Array<String> cryptoGlobalKeyNames = new Array<>(String.class);
    private static final Array<String> cryptoGlobalKeys = new Array<>(String.class);

    public static synchronized void addGlobalKey(String forKey, String withKey) {
        // Check if already exists
        for(int c = 0; c < cryptoGlobalKeyNames.size; c++) {
            String existingForKey = cryptoGlobalKeyNames.items[c];
            if(existingForKey.equals(forKey)) {
                cryptoGlobalKeys.items[c] = withKey;
                return;
            }
        }

        // Insert sorting from longest to shortest
    	int len = forKey.length();
    	int index = 0;
    	for(; index < cryptoGlobalKeyNames.size; index++) {
    		String k = cryptoGlobalKeyNames.items[index];
    		if(k.length() < len)
    			break;
		}
		cryptoGlobalKeyNames.insert(index, forKey);
		cryptoGlobalKeys.insert(index, withKey);
	}

	public static synchronized void removeGlobalKey(String forKey) {
        for(int c = 0; c < cryptoGlobalKeyNames.size; c++) {
            String keyPrefix = cryptoGlobalKeyNames.items[c];

            if(keyPrefix.startsWith(forKey)) {
                cryptoGlobalKeyNames.removeIndex(c);
                cryptoGlobalKeys.removeIndex(c);
                c--;
            }
        }
	}

	private static synchronized String useGlobalKey(String key) {
        for(int c = 0; c < cryptoGlobalKeyNames.size; c++) {
            String k = cryptoGlobalKeyNames.items[c];
            if(key.startsWith(k)) {
                return key + cryptoGlobalKeys.items[c];
            }
        }
        return key;     // passthru
    }

    /**
     * Murmur3 128-bit variant.
     *
     * @param key   - input byte array
     * @param offset - the first element of array
     * @param length - length of array
     * @return - hashcode (2 longs)
     */
    public static byte[] cryptoKeyHash(byte[] key, int offset, int length) {
        final long C1 = 0x87c37b91114253d5L;
        final long C2 = 0x4cf5ad432745937fL;
        final int R1 = 31;
        final int R2 = 27;
        final int R3 = 33;
        final int M = 5;
        final int N1 = 0x52dce729;
        final int N2 = 0x38495ab5;

        long h1 = 0; // = seed;
        long h2 = 0; // = seed;
        final int nblocks = length >> 4;

        // body
        for (int i = 0; i < nblocks; i++) {
            final int i16 = i << 4;
            long k1 = ((long) key[offset + i16] & 0xff)
                    | (((long) key[offset + i16 + 1] & 0xff) << 8)
                    | (((long) key[offset + i16 + 2] & 0xff) << 16)
                    | (((long) key[offset + i16 + 3] & 0xff) << 24)
                    | (((long) key[offset + i16 + 4] & 0xff) << 32)
                    | (((long) key[offset + i16 + 5] & 0xff) << 40)
                    | (((long) key[offset + i16 + 6] & 0xff) << 48)
                    | (((long) key[offset + i16 + 7] & 0xff) << 56);

            long k2 = ((long) key[offset + i16 + 8] & 0xff)
                    | (((long) key[offset + i16 + 9] & 0xff) << 8)
                    | (((long) key[offset + i16 + 10] & 0xff) << 16)
                    | (((long) key[offset + i16 + 11] & 0xff) << 24)
                    | (((long) key[offset + i16 + 12] & 0xff) << 32)
                    | (((long) key[offset + i16 + 13] & 0xff) << 40)
                    | (((long) key[offset + i16 + 14] & 0xff) << 48)
                    | (((long) key[offset + i16 + 15] & 0xff) << 56);

            // mix functions for k1
            k1 *= C1;
            k1 = Long.rotateLeft(k1, R1);
            k1 *= C2;
            h1 ^= k1;
            h1 = Long.rotateLeft(h1, R2);
            h1 += h2;
            h1 = h1 * M + N1;

            // mix functions for k2
            k2 *= C2;
            k2 = Long.rotateLeft(k2, R3);
            k2 *= C1;
            h2 ^= k2;
            h2 = Long.rotateLeft(h2, R1);
            h2 += h1;
            h2 = h2 * M + N2;
        }

        // tail
        long k1 = 0;
        long k2 = 0;
        int tailStart = nblocks << 4;
        switch (length - tailStart) {
            case 15:
                k2 ^= (long) (key[offset + tailStart + 14] & 0xff) << 48;
            case 14:
                k2 ^= (long) (key[offset + tailStart + 13] & 0xff) << 40;
            case 13:
                k2 ^= (long) (key[offset + tailStart + 12] & 0xff) << 32;
            case 12:
                k2 ^= (long) (key[offset + tailStart + 11] & 0xff) << 24;
            case 11:
                k2 ^= (long) (key[offset + tailStart + 10] & 0xff) << 16;
            case 10:
                k2 ^= (long) (key[offset + tailStart + 9] & 0xff) << 8;
            case 9:
                k2 ^= (long) (key[offset + tailStart + 8] & 0xff);
                k2 *= C2;
                k2 = Long.rotateLeft(k2, R3);
                k2 *= C1;
                h2 ^= k2;

            case 8:
                k1 ^= (long) (key[offset + tailStart + 7] & 0xff) << 56;
            case 7:
                k1 ^= (long) (key[offset + tailStart + 6] & 0xff) << 48;
            case 6:
                k1 ^= (long) (key[offset + tailStart + 5] & 0xff) << 40;
            case 5:
                k1 ^= (long) (key[offset + tailStart + 4] & 0xff) << 32;
            case 4:
                k1 ^= (long) (key[offset + tailStart + 3] & 0xff) << 24;
            case 3:
                k1 ^= (long) (key[offset + tailStart + 2] & 0xff) << 16;
            case 2:
                k1 ^= (long) (key[offset + tailStart + 1] & 0xff) << 8;
            case 1:
                k1 ^= (long) (key[offset + tailStart] & 0xff);
                k1 *= C1;
                k1 = Long.rotateLeft(k1, R1);
                k1 *= C2;
                h1 ^= k1;
        }

        // finalization
        h1 ^= length;
        h2 ^= length;

        h1 += h2;
        h2 += h1;

        h1 ^= (h1 >>> 33);
        h1 *= 0xff51afd7ed558ccdL;
        h1 ^= (h1 >>> 33);
        h1 *= 0xc4ceb9fe1a85ec53L;
        h1 ^= (h1 >>> 33);

        h2 ^= (h2 >>> 33);
        h2 *= 0xff51afd7ed558ccdL;
        h2 ^= (h2 >>> 33);
        h2 *= 0xc4ceb9fe1a85ec53L;
        h2 ^= (h2 >>> 33);

        h1 += h2;
        h2 += h1;

        return new byte[] {
                (byte) (h1 >> 56),
                (byte) (h1 >> 48),
                (byte) (h1 >> 40),
                (byte) (h1 >> 32),
                (byte) (h1 >> 24),
                (byte) (h1 >> 16),
                (byte) (h1 >> 8),
                (byte) h1,
                (byte) (h2 >> 56),
                (byte) (h2 >> 48),
                (byte) (h2 >> 40),
                (byte) (h2 >> 32),
                (byte) (h2 >> 24),
                (byte) (h2 >> 16),
                (byte) (h2 >> 8),
                (byte) h2,
        };
    }

    public static byte[] cryptoGenerateIV() {
        SecureRandom sr = secureRandomLocal.get();

        // Generate random IV
        byte[] iv = new byte[CRYPTO_IV_SIZE];
        sr.nextBytes(iv);
        return iv;
    }

    public static int cryptoDecodeIV(byte[] iv, byte[] keyHash) {
        // Prepare selector and mask
        int mask1 = ((keyHash[0] & 0xFF) << 24) | ((keyHash[1] & 0xFF) << 16) | ((keyHash[2] & 0xFF) << 8) | (keyHash[3] & 0xFF);
        int mask2 = ((keyHash[4] & 0xFF) << 24) | ((keyHash[5] & 0xFF) << 16) | ((keyHash[6] & 0xFF) << 8) | (keyHash[7] & 0xFF);
        int mask3 = ((keyHash[8] & 0xFF) << 24) | ((keyHash[9] & 0xFF) << 16) | ((keyHash[10] & 0xFF) << 8) | (keyHash[11] & 0xFF);
        int mask4 = ((keyHash[12] & 0xFF) << 24) | ((keyHash[13] & 0xFF) << 16) | ((keyHash[14] & 0xFF) << 8) | (keyHash[15] & 0xFF);

        // Read meta
        int offset = (((mask1 * 7) + (mask2 * 31) + (mask3 * 131) + (mask4 * 233)) & CRYPTO_IV_SIZE_MASK) % (CRYPTO_IV_SIZE - 16);
        int meta1 = ((iv[offset] & 0xFF) << 24) | ((iv[offset + 1] & 0xFF) << 16) | ((iv[offset + 2] & 0xFF) << 8) | (iv[offset + 3] & 0xFF);
        int meta2 = ((iv[offset + 4] & 0xFF) << 24) | ((iv[offset + 5] & 0xFF) << 16) | ((iv[offset + 6] & 0xFF) << 8) | (iv[offset + 7] & 0xFF);
        int meta3 = ((iv[offset + 8] & 0xFF) << 24) | ((iv[offset + 9] & 0xFF) << 16) | ((iv[offset + 10] & 0xFF) << 8) | (iv[offset + 11] & 0xFF);
        int meta4 = ((iv[offset + 12] & 0xFF) << 24) | ((iv[offset + 13] & 0xFF) << 16) | ((iv[offset + 14] & 0xFF) << 8) | (iv[offset + 15] & 0xFF);

        // Unmask with key hash
        meta1 ^= mask1;
        meta2 ^= mask2;
        meta3 ^= mask3;
        meta4 ^= mask4;

        // Unmask with meta IV
        meta1 ^= meta3;
        meta2 ^= meta4;

        // Simple check
        if(meta1 != meta2)
            throw new IllegalArgumentException("Invalid key or data corruption");
        return meta1;
    }

    public static int cryptoEncodeIV(byte[] iv, byte[] keyHash, int meta) {
        SecureRandom sr = secureRandomLocal.get();

        // Prepare selector and mask
        int mask1 = ((keyHash[0] & 0xFF) << 24) | ((keyHash[1] & 0xFF) << 16) | ((keyHash[2] & 0xFF) << 8) | (keyHash[3] & 0xFF);
        int mask2 = ((keyHash[4] & 0xFF) << 24) | ((keyHash[5] & 0xFF) << 16) | ((keyHash[6] & 0xFF) << 8) | (keyHash[7] & 0xFF);
        int mask3 = ((keyHash[8] & 0xFF) << 24) | ((keyHash[9] & 0xFF) << 16) | ((keyHash[10] & 0xFF) << 8) | (keyHash[11] & 0xFF);
        int mask4 = ((keyHash[12] & 0xFF) << 24) | ((keyHash[13] & 0xFF) << 16) | ((keyHash[14] & 0xFF) << 8) | (keyHash[15] & 0xFF);

        // Unmasked meta
        int meta1 = meta;
        int meta2 = meta;
        int meta3 = sr.nextInt();
        int meta4 = sr.nextInt();

        // Mask with meta IV
        meta1 ^= meta3;
        meta2 ^= meta4;

        // Mask with key hash
        meta1 ^= mask1;
        meta2 ^= mask2;
        meta3 ^= mask3;
        meta4 ^= mask4;

        // Insert meta into IV
        int offset = (((mask1 * 7) + (mask2 * 31) + (mask3 * 131) + (mask4 * 233)) & CRYPTO_IV_SIZE_MASK) % (CRYPTO_IV_SIZE - 16);
        iv[offset] = (byte) (meta1 >> 24);
        iv[offset + 1] = (byte) (meta1 >> 16);
        iv[offset + 2] = (byte) (meta1 >> 8);
        iv[offset + 3] = (byte) meta1;
        iv[offset + 4] = (byte) (meta2 >> 24);
        iv[offset + 5] = (byte) (meta2 >> 16);
        iv[offset + 6] = (byte) (meta2 >> 8);
        iv[offset + 7] = (byte) meta2;
        iv[offset + 8] = (byte) (meta3 >> 24);
        iv[offset + 9] = (byte) (meta3 >> 16);
        iv[offset + 10] = (byte) (meta3 >> 8);
        iv[offset + 11] = (byte) meta3;
        iv[offset + 12] = (byte) (meta4 >> 24);
        iv[offset + 13] = (byte) (meta4 >> 16);
        iv[offset + 14] = (byte) (meta4 >> 8);
        iv[offset + 15] = (byte) meta4;

        return offset;
    }

    public static void cryptoCipher(byte[] data, int offset, byte[] iv, int rotation, byte[] keyHash, int length) {
        for(int maxBlocks = (length / CRYPTO_IV_SIZE) + 1, block = 0, o = 0; block < maxBlocks; block++) {
            int keyRotation1 = ((keyHash[(block * 7) & CRYPTO_KEY_SIZE_MASK] & 0xFF) << 8) | (keyHash[(block * 47) & CRYPTO_KEY_SIZE_MASK] & 0xFF);
            int keyRotation2 = ((keyHash[(block * 107) & CRYPTO_KEY_SIZE_MASK] & 0xFF) << 8) | (keyHash[(block * 17) & CRYPTO_KEY_SIZE_MASK] & 0xFF);

            int ivRotation1 = ((iv[(block * 233) & CRYPTO_IV_SIZE_MASK] & 0xFF) << 8) | (iv[(block * 73) & CRYPTO_IV_SIZE_MASK] & 0xFF);
            int ivRotation2 = ((iv[(block * 29) & CRYPTO_IV_SIZE_MASK] & 0xFF) << 8) | (iv[(block * 151) & CRYPTO_IV_SIZE_MASK] & 0xFF);

            int rotation1 = (rotation + (keyRotation1 * ivRotation1 * 443)) & CRYPTO_IV_SIZE_MASK;
            int rotation2 = (rotation + (keyRotation2 * ivRotation2 * 97)) & CRYPTO_IV_SIZE_MASK;

            for(int c = 0; c < CRYPTO_IV_SIZE && o < length; c++, o++) {
                data[offset + o] ^= iv[(rotation1 + c) & CRYPTO_IV_SIZE_MASK] ^ iv[(rotation2 - c) & CRYPTO_IV_SIZE_MASK] ^ keyHash[c & CRYPTO_KEY_SIZE_MASK];
            }
        }
    }

    // State identifiers
	static final int IDX_NOT_DESERIALIZING = -1;
	static final int IDX_INLINE = -2;
	static final int IDX_DESERIALIZATION_MAP = -3;



    protected static final int NOT_FOUND = -1;
	
	protected static final int ID_NULL = 0;
	protected static final int ID_UNIQUE = 1;
	protected static final int ID_INLINED = 2;
	protected static final int ID_REFERENCED = 3;
	protected static final int ID_BITSHIFT = 2;
	protected static final int ID_BITMASK = 3;
	
	protected static final int TYPE_UNCOMPRESSED = 0;
	protected static final int TYPE_COMPRESSED = 1;
	protected static final int TYPE_BITSHIFT = 1;

	public static Object[] concat(Object[] to, Object ... elements) {
		Object[] result = Arrays.copyOf(to, to.length + elements.length);
		System.arraycopy(elements, 0, result, to.length, elements.length);
		return result;
	}

	public static final Class<?> parseClassName(String name) {
		Class<?> type;
		// Else find class
		if(name.equals("byte"))
			type = byte.class;
		else if(name.equals("short"))
			type = short.class;
		else if(name.equals("int"))
			type = int.class;
		else if(name.equals("long"))
			type = long.class;
		else if(name.equals("char"))
			type = char.class;
		else if(name.equals("float"))
			type = float.class;
		else if(name.equals("double"))
			type = double.class;
		else if(name.equals("boolean"))
			type = boolean.class;
		else if(name.equals("void"))
			type = void.class;
		else {
			try {
				type = Class.forName(name);
			} catch (ClassNotFoundException e) {
				return null;
			}
		}
		
		return type;
	}
	
	private static final IdentityHashMap<Class<?>, Serializer<?>> defaultSerializers = new IdentityHashMap<Class<?>, Serializer<?>>();
	
	public static Map<Class<?>, Serializer<?>> findSerializers(Class<?> serializedType) {
		Map<Class<?>, Serializer<?>> serializers = new IdentityHashMap<Class<?>, Serializer<?>>();
		synchronized(defaultSerializers) {
			for(Entry<Class<?>, Serializer<?>> e : defaultSerializers.entrySet()) {
				Class<?> type = e.getKey();
				if(serializedType.isAssignableFrom(type))
					serializers.put(type, e.getValue());
			}
		}
		return serializers;
	}
	
	public static Serializer<?> findSerializer(Class<?> serializedType) {
		// Check if DefaultSerializer annotation is present
		Class<?> serializerType;
		if(serializedType.isAnnotationPresent(DefaultSerializer.class))
			serializerType = serializedType.getAnnotation(DefaultSerializer.class).value();
		else 
			serializerType = null;
		return findSerializer(serializedType, serializerType);
	}
	
	public static Serializer<?> findSerializer(Class<?> serializedType, Class<?> serializerType) {
		Serializer<?> serializer = null;
		// Find best serializer
		Class<?> superType = serializedType;
		synchronized(defaultSerializers) {
			// Check if an array
			if(serializedType.isArray()) {
				serializer = defaultSerializers.get(serializedType);
				if(serializer == null)
					serializer = defaultSerializers.get(Object[].class);		// Default object array serializer
				return serializer;
			}
			while(true) {
				// Find serializer for concrete type
				serializer = defaultSerializers.get(superType);
				if(serializer != null || superType == Object.class)
					break;		// Serializer was found or serializing Object type
				// Else serializer was not found for concrete type, try to find for one of its interfaces
				Class<?>[] interfaces = superType.getInterfaces();
				for(int c = 0; c < interfaces.length; c++) {
					serializer = defaultSerializers.get(interfaces[c]);
					if(serializer != null)
						break;
				}
				if(serializer != null) {
					superType = Object.class;			// If using interface serializer, always create new instance for specified concrete type
					break;
				}
				superType = superType.getSuperclass();
			}
		}
		// Check if found serializer
		if(serializer == null) {
			if(serializerType == null)
				return null;	// none found (should not happen if FieldSerializer is set for Object.class, and no hints what serializer to use
		}
		else if(serializerType == null || serializer.getClass() == serializerType) {
			// Else serializer is found is same with hinted serializer type
			// If current serializer is used for a super type, check if a custom serializer is needed
			if(serializedType != superType) {
				try {
					serializer = serializer.getClass().getConstructor(Class.class).newInstance(serializedType);
				} catch (NoSuchMethodException e) {
					// No custom serializer required, just use existing serializer
				} catch (Throwable e) {
					throw new MassException("Failed to create inferred custom serializer: " + serializer.getClass(), e);
				}
			}
			return serializer;
		}
		// Else serializerType was hinted, and, serializer was not found or serializer does not match hinted type
		// So create new instance of hinted serializer
		try {
			serializer = (Serializer<?>) serializerType.getConstructor(Class.class).newInstance(serializedType);
		} catch (NoSuchMethodException e) {
			// No need to create custom serializer, try a default one zero-arg ctor 
			try {
				serializer = (Serializer<?>) serializerType.newInstance();
			} catch(Throwable e2) {
				throw new MassException("Failed to create specified serializer: " + serializerType, e2);
			}
		} catch (Throwable e) {
			throw new MassException("Failed to create specified custom serializer: " + serializerType, e);
		}
		// Done
		return serializer;
	}
	
	public static void registerSerializer(Class<?> serializedType, Serializer<?> serializer) {
		synchronized(defaultSerializers) {
			defaultSerializers.put(serializedType, serializer);
		}
	}
	
	public static void registerSerializers(Map<Class<?>, Serializer<?>> serializers) {
		synchronized(defaultSerializers) {
			for(Entry<Class<?>, Serializer<?>> entry : serializers.entrySet()) {
				defaultSerializers.put(entry.getKey(), entry.getValue());
			}
		}
	}
	
	public static void registerSerializers(Class<?>[] serializedTypes, Serializer<?>[] serializers) {
		if(serializedTypes.length != serializers.length)
			throw new IllegalArgumentException("serializedTypes.length and serializers.length must be equal");
		synchronized(defaultSerializers) {
			for(int c = 0; c < serializedTypes.length; c++)
				defaultSerializers.put(serializedTypes[c], serializers[c]);
		}
	}
	
	public static void registerDefaultSerializers() {
		HashMap<Class<?>, Serializer<?>> serializers = new HashMap<Class<?>, Serializer<?>>();
		serializers.put(byte[].class, new ByteArraySerializer());
		serializers.put(char[].class, new CharArraySerializer());
		serializers.put(short[].class, new ShortArraySerializer());
		serializers.put(int[].class, new IntArraySerializer());
		serializers.put(long[].class, new LongArraySerializer());
		serializers.put(float[].class, new FloatArraySerializer());
		serializers.put(double[].class, new DoubleArraySerializer());
		serializers.put(boolean[].class, new BooleanArraySerializer());
		serializers.put(String[].class, new StringArraySerializer());
		serializers.put(Object[].class, new ObjectArraySerializer());
		serializers.put(BigInteger.class, new BigIntegerSerializer());
		serializers.put(BigDecimal.class, new BigDecimalSerializer());
		serializers.put(Class.class, new ClassSerializer());
		serializers.put(Date.class, new DateSerializer());
		serializers.put(Enum.class, new EnumSerializer());
		serializers.put(EnumSet.class, new EnumSetSerializer());
		serializers.put(Currency.class, new CurrencySerializer());
		serializers.put(StringBuffer.class, new StringBufferSerializer());
		serializers.put(StringBuilder.class, new StringBuilderSerializer());
		serializers.put(Collections.EMPTY_LIST.getClass(), new CollectionsEmptyListSerializer());
		serializers.put(Collections.EMPTY_MAP.getClass(), new CollectionsEmptyMapSerializer());
		serializers.put(Collections.EMPTY_SET.getClass(), new CollectionsEmptySetSerializer());
		serializers.put(Collections.singletonList(null).getClass(), new CollectionsSingletonListSerializer());
		serializers.put(Collections.singletonMap(null, null).getClass(), new CollectionsSingletonMapSerializer());
		serializers.put(Collections.singleton(null).getClass(), new CollectionsSingletonSetSerializer());
		serializers.put(Collection.class, new CollectionSerializer());
		serializers.put(TreeMap.class, new TreeMapSerializer());
		serializers.put(Map.class, new MapSerializer());
		serializers.put(MassSerializable.class, new MassSerializableSerializer());
		serializers.put(TimeZone.class, new TimeZoneSerializer());
		serializers.put(Calendar.class, new CalendarSerializer());
		// Primitives and string
		serializers.put(int.class, new IntSerializer());
		serializers.put(float.class, new FloatSerializer());
		serializers.put(boolean.class, new BooleanSerializer());
		serializers.put(byte.class, new ByteSerializer());
		serializers.put(char.class, new CharSerializer());
		serializers.put(short.class, new ShortSerializer());
		serializers.put(long.class, new LongSerializer());
		serializers.put(double.class, new DoubleSerializer());
		serializers.put(Integer.class, new IntSerializer());
		serializers.put(Float.class, new FloatSerializer());
		serializers.put(Boolean.class, new BooleanSerializer());
		serializers.put(Byte.class, new ByteSerializer());
		serializers.put(Character.class, new CharSerializer());
		serializers.put(Short.class, new ShortSerializer());
		serializers.put(Long.class, new LongSerializer());
		serializers.put(Double.class, new DoubleSerializer());
		serializers.put(String.class, new StringSerializer());
		serializers.put(Object.class, new FieldSerializer<Object>(Object.class));
		
		registerSerializers(serializers);
	}
	
	public static <T> T newInstance (Class<T> type) {
		try {
			return type.newInstance();
		} catch (Throwable e) {
			throw new MassException("Unable to instantiate type: " + type, e);
		}
	}
	
	static {
		registerDefaultSerializers();
	}

	// Classes
	private final IdentityObjectIntMap<Class<?>> classToId = new IdentityObjectIntMap<Class<?>>(16, 0.8f);
	private Class<?>[] classes = null;
	private Serializer<?>[] serializers = null;
	private int currentSerializersId = 0;

	// Types and offsets
	private int[] types = null;
	private int[] offsets = null;
	// Map
	private int[] mapOffsets = null;
	private int[] map = null;

	private int mapSize = 0;
	// Data
	private byte[] data = null;
	private int dataSize = 0;
	
	// Buffer
	private final Output output = new Output(64);
	private final Input input = new Input();
	// Compression

	// Serialization
	private final IntMap<Object> idToObj = new IntMap<Object>(16, 0.8f);
	private int currentSerialized = 0;
	private int totalSerialized = 0;
	
	// Deserialization
	private final IntArray deserializationList = new IntArray();
	private int deserializingIdx = IDX_NOT_DESERIALIZING;
	private int mapOffset = 0;
	private int mapLimit = 0;

	public void clear() {
		// Clear all data
		classToId.clear();
		classes = null;
		serializers = null;
		currentSerializersId = 0;
		// Types and offsets
		types = null;
		offsets = null;
		// Map
		mapOffsets = null;
		map = null;
		mapSize = 0;
		// Data
		data = null;
		dataSize = 0;
		
		// Reset buffers and compressors
		output.clear();
		input.clear();

		// Object buffers
		idToObj.clear();
		
		// Serialization
		currentSerialized = 0;
		totalSerialized = 0;
		
		// Deserialization
		deserializationList.clear();
		deserializingIdx = IDX_NOT_DESERIALIZING;
		mapOffset = 0;
		mapLimit = 0;
	}
	
	public int getNumObjects() {
		return totalSerialized;
	}

	protected void ensureSerializerCapacity(int numSerializers) {
		if(classes == null) {
			classes = new Class<?>[numSerializers];
			serializers = new Serializer<?>[numSerializers];
			return;
		}
		if(numSerializers <= classes.length)
			return;		// prevent UB
		int newSize = classes.length * 2;
		if(newSize == 0)
			newSize = numSerializers;
		while(newSize < numSerializers)
			newSize *= 2;
		classes = Arrays.copyOf(classes, newSize);
		serializers = Arrays.copyOf(serializers, newSize);
	}
	
	
	protected void ensureObjectsCapacity(int numObjects) {
		if(offsets == null) {
			types = new int[numObjects];
			offsets = new int[numObjects];
			mapOffsets = new int[numObjects];
			return;
		}
		if(numObjects <= offsets.length)
			return;		// prevent UB
		int newSize = offsets.length * 2;
		if(newSize == 0)
			newSize = numObjects;
		while(newSize < numObjects)
			newSize *= 2;
		types = Arrays.copyOf(types, newSize);
		offsets = Arrays.copyOf(offsets, newSize);
		mapOffsets = Arrays.copyOf(mapOffsets, newSize);
	}
	
	protected void ensureMapCapacity(int mapSize) {
		if(map == null) {
			map = new int[mapSize];
			return;
		}
		if(mapSize <= map.length)
			return;		// prevent UB
		int newSize = map.length * 2;
		if(newSize == 0)
			newSize = mapSize;
		while(newSize < mapSize)
			newSize *= 2;
		map = Arrays.copyOf(map, newSize);
	}
	
	protected void ensureDataCapacity(int numBytes) {
		if(data == null) {
			data = new byte[numBytes];
			return;
		}
		if(numBytes <= data.length)
			return;		// prevent UB
		int newSize = data.length * 2;
		if(newSize == 0)
			newSize = numBytes;
		while(newSize < numBytes)
			newSize *= 2;
		data = Arrays.copyOf(data, newSize);
	}
	
	public void register(Class<?> type, Serializer<?> serializer, int id) {
		if(type != null)
			classToId.put(type, id);
		ensureSerializerCapacity(id + 1);
		classes[id] = type;
		serializers[id] = serializer;
	}
	
	public int register(Class<?> type, Serializer<?> serializer) {
		int id = classToId.get(type, -1);
		// If id does not exist, find a new one
		if(id == -1) {
			ensureSerializerCapacity(currentSerializersId + 1);
			while(classes[currentSerializersId] != null) {
				currentSerializersId++;
				ensureSerializerCapacity(currentSerializersId + 1);
			}
			id = currentSerializersId;
			classToId.put(type, id);
		}
		// Save to serializer
		classes[id] = type;
		serializers[id] = serializer;
		return id;
	}
	
	public Serializer<?> register(Class<?> type) {
		Serializer<?> serializer = getSerializer(type);
		if(serializer == null) {
			// Else not found, find now
			serializer = findSerializer(type);
			if(serializer == null)
				throw new MassException("Unable to find serializer for class: " + type);
			register(type, serializer);
		}
		return serializer;
	}
	
	public int getSerializedClassId(Class<?> type) {
		return classToId.get(type, -1);
	}
	
	public Class<?> getSerializedClass(int id) {
		if(id >= classes.length)
			return null;
		return classes[id];
	}
	
	public Serializer<?> getSerializer(int id) {
		if(id >= serializers.length)
			return null;
		return serializers[id];
	}
	
	public Serializer<?> getSerializer(Class<?> type) {
		// Find serializer for type
		int id = classToId.get(type, -1);
		if(id == -1)
			return null;
		return serializers[id];
	}
	
	public int getType (int idx) {
		if(idx >= totalSerialized || idx < 0)
			throw new IndexOutOfBoundsException("Index out of bounds, must be >= 0 && < " + totalSerialized);
		return types[idx] >> TYPE_BITSHIFT;
	}

	public void load(Input s, String formatName, String key) {
	    load(s, formatName, useGlobalKey(key).getBytes(charset));
    }
	
	public void load(Input s, String formatName, byte[] key) {
		if(currentSerialized != totalSerialized)
			throw new IllegalStateException("Cannot read while serializing");
		else if(currentSerialized != 0)
			throw new IllegalStateException("Cannot read as mass is not empty");

		Inflater inflater = inflaterLocal.get();
			
		// Read format identifier
		if(!s.readFixedString(formatName))
			throw new MassException("Incompatible format, expected: " + formatName);

		try {
            // Crypto
            // Generate key hash
            byte[] keyHash = cryptoKeyHash(key, 0, key.length);

            // Read iv
            byte[] iv = new byte[CRYPTO_IV_SIZE];
            s.readBytes(iv);

            // Read writeSize meta from IV
            int writeSize = cryptoDecodeIV(iv, keyHash);

            // Read format
            // Format size
			boolean isCompressed = (writeSize & 0x1) != 0;		// Determine whether format data is compressed
			writeSize = writeSize >> 1;
			// Read format data
			try {
				output.writeBytes(s, writeSize);

				byte[] buffer = output.getBuffer();

				// Decrypt format data
                cryptoCipher(buffer, 0, iv, 0, keyHash, writeSize);

                // Decompress if needed
				if(isCompressed) {
					output.inflate(inflater, 0, writeSize);
					input.setBuffer(output.getBuffer(), writeSize, output.position());
				}
				else
					input.setBuffer(output.getBuffer(), 0, writeSize);
			} finally {
				output.clear();
			}

			// Number of objects
			currentSerialized = totalSerialized = input.readInt();
			if(currentSerialized == 0)
				return;
			
			ensureObjectsCapacity(currentSerialized);
			
			// Read types
			for(int c = 0; c < currentSerialized; c++)
				types[c] = input.readInt();
			
			// Read offsets
			for(int c = 0; c < currentSerialized; c++)
				offsets[c] = input.readInt();
			
			// Read map offsets
			for(int c = 0; c < currentSerialized; c++)
				mapOffsets[c] = input.readInt();
			
			// Read map
			mapSize = mapOffsets[currentSerialized - 1];
			ensureMapCapacity(mapSize);
			for(int c = 0; c < mapSize; c++)
				map[c] = input.readInt();
			
			// Read serializers
			currentSerializersId = input.readInt();
			ensureSerializerCapacity(currentSerializersId);
			for(int c = 0; c < currentSerializersId; c++) {
				int id = input.readInt();
				String serializedTypeName = input.readString();
				String serializerTypeName = input.readString();
				if(getSerializer(id) != null)
					continue;			// Serializer already loaded
				Class<?> serializedType = parseClassName(serializedTypeName);
				Class<?> serializerType = parseClassName(serializerTypeName);
				Serializer<?> serializer;
				if(serializedType == null || serializerType == null) {
					if(serializedType == null && serializerType == null)
						serializer = new FailedSerializer("Failed to find type: " + serializedTypeName + " and serializer: " + serializerTypeName, null);					
					else if(serializedType == null)
						serializer = new FailedSerializer("Failed to find type: " + serializedTypeName, null);
					else // if(serializerType == null)
						serializer = new FailedSerializer("Failed to find serializer: " + serializerTypeName, null);					
				}
				else {
					try {
						serializer = findSerializer(serializedType, serializerType);
					} catch(Throwable e) {
						serializer = new FailedSerializer("Failed to create serializer: " + serializerTypeName + " for type: " + serializedTypeName, e);
					}
				}
				register(serializedType, serializer, id);
			}
			
			// Read format
			readFormatData(input);
			
			// Done reading format, read data
			dataSize = offsets[currentSerialized - 1];
			ensureDataCapacity(dataSize);
			s.readBytes(data, 0, dataSize);

            // Decrypt data
            cryptoCipher(data, 0, iv, writeSize, keyHash, dataSize);

		} catch(Throwable e) {
			clear();
			throw new MassException("Failed to read mass", e);
		}
	}

	public void reference (Object object) {
		// Update the latest object with this object
		if(deserializingIdx < 0)
			return;		// not deserializing any object, or inline object
		idToObj.put(deserializingIdx, object);
		cacheDeserializedObject(deserializingIdx, object);
	}
	
	public <T> T read () {
		// Check if can read map
		if(mapOffset == mapLimit)
			throw new MassException("No object data available");
		// Get index
		int idx = map[mapOffset++];
		// Parse index
		int idType = idx & ID_BITMASK;
		int idReference = idx >> ID_BITSHIFT;
		switch(idType) {
		
		case ID_NULL:
			return null;	// null object
			
		case ID_UNIQUE:
			{
				T o = (T) getUniqueObject(idReference);
				if(o == null)
					throw new MassException("Failed to get unique object: " + idReference);
				return o;
			}
			
		case ID_INLINED: 
			{
				Class<?> type = getSerializedClass(idReference);
				Serializer serializer = getSerializer(idReference);
				if(serializer == null)
					throw new MassException("Unable to find serializer for type: " + idReference + ", for inlined object");
				int currentDeserializationIdx = deserializingIdx;
				deserializingIdx = IDX_INLINE;		// inlined objects do not have id's
				try {
					return (T) serializer.read(this, input, type);
				} finally {
					deserializingIdx = currentDeserializationIdx;
				}
			}
		
		default: 	// case ID_REFERENCED:
			return get(idReference, false);
		}
	}
	
	public int buildDeserializationList (int idx, IntArray deserializationList) {
		// Mark list offset
		int listOffset = deserializationList.size;
		// Add initial index
		deserializationList.add(idx);
		// Keep expanding all indices, but do not add duplicate indices
		for(int offset = listOffset; offset < deserializationList.size; offset++) {
			// Expand current idx
			idx = deserializationList.items[offset];
			// Add child objects
			for(int mapOffset = idx == 0 ? 0 : mapOffsets[idx - 1], mapLimit = mapOffsets[idx]; mapOffset < mapLimit; mapOffset++) {
				// Parse index
				int cidx = map[mapOffset];
				// Only proceed if index is referenced
				if((cidx & ID_BITMASK) != ID_REFERENCED)
					continue;
				cidx = cidx >> ID_BITSHIFT;
				// Add only if cidx is not already added
				boolean exists = false;
				for(int c = listOffset; c < deserializationList.size; c++) {
					if(deserializationList.items[c] == cidx) {
						exists = true;
						break;
					}
				}
				if(!exists)
					deserializationList.add(cidx);
			}
		}
		// Now sort, move simple objects (without childs) to the front, and complex objects to the back 
		for(int sortedOffset = listOffset; sortedOffset < deserializationList.size; sortedOffset++) {
			// Find an object whose childs are all sorted
			boolean childsSorted = true;
			for(int unsortedOffset = deserializationList.size - 1; unsortedOffset >= sortedOffset; unsortedOffset--) {
				idx = deserializationList.items[unsortedOffset];
				childsSorted = true;
				// Check if all child objects has been sorted
				for(int mapOffset = idx == 0 ? 0 : mapOffsets[idx - 1], mapLimit = mapOffsets[idx]; mapOffset < mapLimit; mapOffset++) {
					// Parse index
					int cidx = map[mapOffset];
					// Only proceed if index is referenced
					if((cidx & ID_BITMASK) != ID_REFERENCED)
						continue;
					cidx = cidx >> ID_BITSHIFT;
					boolean found = false;
					for(int c = listOffset; c < sortedOffset; c++) {
						if(cidx == deserializationList.items[c]) {
							found = true;
							break;
						}
					}
					// If not found, childs are not sorted yet
					if(!found) {
						childsSorted = false;
						break;
					}
				}
				// If all childs are sorted, move to sorted region
				if(childsSorted) {
					int unsortedIdx = deserializationList.items[sortedOffset];
					deserializationList.items[sortedOffset] = idx;
					deserializationList.items[unsortedOffset] = unsortedIdx;
                    // Try to continue from this loop for performance' sake
                    sortedOffset++;
                    if(sortedOffset < deserializationList.size)
                        continue;
					break;
				}
			}
			if(!childsSorted)
				break;			// no more sorting possible
		}
		return deserializationList.size - listOffset;		// return list size
	}
	
	public <T> T get (int idx, boolean useDeserializationList) {
		if(idx >= totalSerialized || idx < 0)
			throw new IndexOutOfBoundsException("Index out of bounds, must be >= 0 && < " + totalSerialized);
		// Check if already deserialized
		T o = (T) idToObj.get(idx);
		if(o != null)
			return o;
		// Check format cache
		o = (T) findDeserializedObject(idx);
		if(o != null) {
			// Cache to reader if can
			if(deserializingIdx != IDX_NOT_DESERIALIZING)
				idToObj.put(idx, o);
			return o;
		}
		if(currentSerialized != totalSerialized)
			throw new IllegalStateException("Cannot get while serializing");
		// Determine type, serializer and compression
		boolean isCompressed = (types[idx] & TYPE_COMPRESSED) != 0;
		int typeId = types[idx] >> TYPE_BITSHIFT;
		Class<?> type = getSerializedClass(typeId);
		Serializer serializer = getSerializer(typeId);
		if(serializer == null)
			throw new MassException("Unable to find serializer for type: " + typeId + ", for object: " + idx);

		// Use deserialization map if needed
		if(useDeserializationList) {
			int listOffset = deserializationList.size;
			int listSize = buildDeserializationList(idx, deserializationList);
			if(listSize > 0) {
				int currentDeserializingIdx = deserializingIdx;
				deserializingIdx = IDX_DESERIALIZATION_MAP;
				try {
					for(int listLimit = listOffset + listSize; listOffset < listLimit; listOffset++) {
						// Deserialize this child
						int cidx = deserializationList.items[listOffset];
						if(cidx != idx)
							get(cidx, false);
					}
					// Check if deserializing childs inadvertently deserialized idx
					o = (T) idToObj.get(idx);
					if(o != null)
						return o;
				} catch(Throwable e) {
					throw new MassException("Deserialization list failed for object[" + idx + "] type: " + type + " serializer: " + serializer, e);
				} finally {
					deserializationList.size = listOffset;
					deserializingIdx = currentDeserializingIdx;
					if(o != null && deserializingIdx == IDX_NOT_DESERIALIZING)
						idToObj.clear();
				}
			}
		}
		// Remember current state
		byte[] currentBuffer = input.getBuffer();
		int currentPosition = input.position();
		int currentLimit = input.limit();
		int outputLimit = output.position();
		int currentMapOffset = mapOffset;
		int currentMapLimit = mapLimit;
		int currentDeserializingIdx = deserializingIdx;
		deserializingIdx = idx;

		Inflater inflater = inflaterLocal.get();

		try {
			// Determine data region
			int dataOffsetStart = idx == 0 ? 0 : offsets[idx - 1];
			int dataOffsetEnd = offsets[idx];
			if(isCompressed) {
				// Decompress data
				output.inflate(inflater, data, dataOffsetStart, dataOffsetEnd - dataOffsetStart);
				input.setBuffer(output.getBuffer(), outputLimit, output.position());
			}
			else
				input.setBuffer(data, dataOffsetStart, dataOffsetEnd);
			// Set map region
			mapOffset = idx == 0 ? 0 : mapOffsets[idx - 1];
			mapLimit = mapOffsets[idx];
			// Deserialize object
			o = (T) serializer.read(this, input, type);
			if(mapOffset < mapLimit)
				throw new MassException("Asymmetric decoding detected, unread objects: " + (mapLimit - mapOffset));
			if(input.remaining() > 0)
				throw new MassException("Asymmetric decoding detected, unread bytes: " + input.remaining());
			// Reference
			reference(o);			// Re-reference it back just in case
			return o;
		} catch(Throwable e) {
			throw new MassException("Failed to decode object[" + idx + "] type: " + type + " serializer: " + serializer, e);
		} finally {
			// Reset state
			input.setBuffer(currentBuffer, currentPosition, currentLimit);
			output.setPosition(outputLimit);
			mapOffset = currentMapOffset;
			mapLimit = currentMapLimit;
			deserializingIdx = currentDeserializingIdx;
			if(deserializingIdx == IDX_NOT_DESERIALIZING)
				idToObj.clear();
		}
	}

	//===== Serialization =====
    public void save(Output s, String formatName, String key) {
	    save(s, formatName, useGlobalKey(key).getBytes(charset));
    }

	public void save(Output s, String formatName, byte[] key) {
		if(currentSerialized != totalSerialized)
			throw new IllegalStateException("Cannot save while serialization is in progress");
		else if(deserializingIdx != IDX_NOT_DESERIALIZING)
			throw new IllegalStateException("Cannot save while deserialization is in progress");


		// Format name
		s.writeFixedString(formatName);

		// Generate format data
        try {
			// Number of objects
			output.writeInt(totalSerialized);
			
			// Write types
			for(int c = 0; c < totalSerialized; c++)
				output.writeInt(types[c]);
			
			// Write offsets
			for(int c = 0; c < totalSerialized; c++)
				output.writeInt(offsets[c]);
			
			// Write map offsets
			for(int c = 0; c < totalSerialized; c++)
				output.writeInt(mapOffsets[c]);
			
			// Write map
			for(int c = 0; c < mapSize; c++)
				output.writeInt(map[c]);
			
			// Write serializers
			if(serializers == null) {
				output.writeInt(0);
			}
			else {
				int numSerializers = 0;
				for(int c = 0; c < serializers.length; c++) {
					if(serializers[c] != null)
						numSerializers++;
				}
				output.writeInt(numSerializers);
				// Write serializers
				for(int c = 0; c < serializers.length; c++) {
					if(serializers[c] == null)
						continue;
					output.writeInt(c);
					output.writeString(classes[c].getName());
					output.writeString(serializers[c].getClass().getName());
				}
			}
			
			// Write format
			writeFormatData(output);

			Deflater deflater = deflaterLocal.get();
			
			// Determine compression
			int uncompressedSize = output.position();
			output.deflate(deflater, 0, uncompressedSize);
			int compressedSize = output.position() - uncompressedSize;
			// Check compression ratio
			float compressionRatio = (float)uncompressedSize / (float)compressedSize;
			boolean isCompressed = compressionRatio >= minCompressionRatio;
			// Write format data
            int writeSize;
            int writeStart;
            int writeLength;
			if(isCompressed) {
			    writeSize = (compressedSize << 1) | 0x1;
				writeStart = uncompressedSize;
				writeLength = compressedSize;
			}
			else {
                writeSize = uncompressedSize << 1;
				writeStart = 0;
				writeLength = uncompressedSize;
			}

			// Crypto
            // Generate IV
            byte[] iv = cryptoGenerateIV();

			// Generate key has
            byte[] keyHash = cryptoKeyHash(key, 0, key.length);

            // Encode writeSize as IV meta
            cryptoEncodeIV(iv, keyHash, writeSize);

            // Write iv
            s.writeBytes(iv);

            // Encrypt format data
            byte[] buffer = output.getBuffer();
            cryptoCipher(buffer, writeStart, iv, 0, keyHash, writeLength);

            // Write format data
            s.write(buffer, writeStart, writeLength);

            // Encrypt data
            if(data != null && dataSize > 0) {
                output.clear();
                output.write(data, 0, dataSize);
                buffer = output.getBuffer();
                cryptoCipher(buffer, 0, iv, writeLength, keyHash, dataSize);

                // Write data
                s.write(buffer, 0, dataSize);
            }
		} finally {
			output.clear();
		}
	}
	
	
	public void write(Object o) {
		if(currentSerialized == totalSerialized)
			throw new IllegalStateException("Currently not serializing, cannot write object");
		// Build id
		int id;
		if(o == null)
			id = ID_NULL;
		else if(shouldInlineObject(o)) {
			Class<?> type = o.getClass();
			Serializer serializer = register(type);
			int typeId = getSerializedClassId(type);
			// Need to inline this object
			id = ID_INLINED | (typeId << ID_BITSHIFT);
			// Serialize
			try {
				serializer.write(this, output, o);
			} catch(Throwable e) {
				throw new MassException("Cannot serialize inlined object: " + o + ", type: " + type, e);
			}
		}
		else if((id = findUniqueId(o)) != -1)
			id = ID_UNIQUE | (id << ID_BITSHIFT);		// Else object is unique
		else {
			// Else need to reference this object
			int idx = add(o);
			id = ID_REFERENCED | (idx << ID_BITSHIFT);
		}
		
		// Add to map
		ensureMapCapacity(mapSize + 1);
		map[mapSize++] = id;
	}
	
	public int add (Object o) {
		if(o == null)
			throw new IllegalArgumentException("Cannot add null objects");
		else if(deserializingIdx != IDX_NOT_DESERIALIZING)
			throw new IllegalStateException("Cannot add objects while deserializing");
		
		// Check if object is recognized
		int id = findSerializedObject(o);
		if(id != NOT_FOUND && id < totalSerialized)
			return id;		// object was added before
		// Else add object
		id = totalSerialized;
		ensureObjectsCapacity(id + 1);
		cacheSerializedObject(o, id);
		idToObj.put(id, o);
		totalSerialized++;
		// Start serializing now if this is the first object
		if(currentSerialized != id)
			return id;		// this is a child object, finish serializing parent object first
		// Else serializing parent object, start
		int prevSerialized = currentSerialized;

		Deflater deflater = deflaterLocal.get();

		try {
			while(currentSerialized < totalSerialized) {
				Object child = idToObj.get(currentSerialized);
				try {
					// Get serializer first
					Class<?> objectType = child.getClass();
					Serializer serializer = register(objectType);
					// Serialize
					serializer.write(this, output, child);
					int uncompressedSize = output.position();
					output.deflate(deflater, 0, uncompressedSize);
					int compressedSize = output.position() - uncompressedSize;
					// Check compression ratio
					float compressionRatio = (float)uncompressedSize / (float)compressedSize;
					boolean isCompressed = compressionRatio >= minCompressionRatio;
					// Save to data
					if(isCompressed) {
						ensureDataCapacity(dataSize + compressedSize);
						System.arraycopy(output.getBuffer(), uncompressedSize, data, dataSize, compressedSize);
						dataSize += compressedSize; 
					}
					else {
						ensureDataCapacity(dataSize + uncompressedSize);
						System.arraycopy(output.getBuffer(), 0, data, dataSize, uncompressedSize);
						dataSize += uncompressedSize; 
					}
					// Update type
					int typeId = getSerializedClassId(objectType) << 1;
					if(isCompressed)
						typeId |= 0x1;		// mark compression
					types[currentSerialized] = typeId;
					// Update offsets
					offsets[currentSerialized] = dataSize;
					mapOffsets[currentSerialized] = mapSize;
					// Done
					currentSerialized++;
				} catch(Throwable e) {
					throw new RuntimeException("Failed to serialize child object[" + currentSerialized + "]: " + child, e);
				} finally {
					output.clear();
				}
			}
		} catch(Throwable e) {
			// Discard previous serialized
			totalSerialized = prevSerialized;
			currentSerialized = prevSerialized;
			// Rethrow e
			throw new RuntimeException("Failed to serialize object[" + currentSerialized + "]: " + o, e);
		} finally {
			idToObj.clear();
		}
		
		return id;
	}
	
	// Overridable methods
	protected void cacheDeserializedObject(int idx, Object object) {
		// Not caching
	}
	protected Object findDeserializedObject(int idx) {
		return null;		// not cached
	}

	protected void cacheSerializedObject(Object object, int idx) {
		// Not caching
	}
	protected int findSerializedObject(Object object) {
		return NOT_FOUND;		// not cached
	}
	
	protected int findUniqueId(Object o) {
		return NOT_FOUND;				// no unique objects
	}
	protected Object getUniqueObject(int id) {
		throw new UnsupportedOperationException("Format does not support unique objects, requested: " + id);		
	}
	
	
	protected void readFormatData(Input s) {
		// Nothing to read
	}
	protected void writeFormatData(Output s) {
		// Nothing to write
	}

	
	protected boolean shouldInlineObject(Object object) {
		// By default, inline primitives
		FieldSerializer.Primitive primitive = FieldSerializer.Primitive.findPrimitive(object.getClass());
		return primitive != FieldSerializer.Primitive.OBJECT;
	}
}
