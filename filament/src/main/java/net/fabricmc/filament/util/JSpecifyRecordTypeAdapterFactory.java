package net.fabricmc.filament.util;

import java.io.IOException;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.jspecify.annotations.Nullable;

public class JSpecifyRecordTypeAdapterFactory implements TypeAdapterFactory {
	@Override
	public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
		Class<? super T> rawType = type.getRawType();
		if (!rawType.isRecord()) return null;

		TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);

		return new TypeAdapter<>() {
			@Override
			public void write(JsonWriter out, T value) throws IOException {
				delegate.write(out, value);
			}

			@Override
			public T read(JsonReader in) throws IOException {
				T instance = delegate.read(in);

				for (RecordComponent component : rawType.getRecordComponents()) {
					AnnotatedType annotatedType = component.getAnnotatedType();
					Object value;

					try {
						Method accessor = component.getAccessor();
						accessor.setAccessible(true);
						value = accessor.invoke(instance);
					} catch (IllegalAccessException | InvocationTargetException e) {
						throw new JsonParseException("Error accessing record component", e);
					}

					if (value == null && !annotatedType.isAnnotationPresent(Nullable.class)) {
						throw new JsonParseException("Non-null component \"" + component.getName() + "\" is null or missing");
					}

					// TODO: Collection support?
				}

				return instance;
			}
		};
	}
}
