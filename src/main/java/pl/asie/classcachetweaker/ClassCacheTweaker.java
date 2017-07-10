/**
 * This file is part of ClassCacheTweaker.
 *
 * ClassCacheTweaker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ClassCacheTweaker is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ClassCacheTweaker.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Additional permission under GNU GPL version 3 section 7
 *
 * If you modify this Program, or any covered work, by linking or
 * combining it with the Minecraft game engine, the Mojang Launchwrapper,
 * the Mojang AuthLib and the Minecraft Realms library (and/or modified
 * versions of said software), containing parts covered by the terms of
 * their respective licenses, the licensors of this Program grant you
 * additional permission to convey the resulting work.
 */
package pl.asie.classcachetweaker;

//import com.google.common.collect.ImmutableSet;
//import com.google.common.collect.Lists;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.*;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.*;

import org.eclipse.collections.impl.set.immutable.*;
import org.eclipse.collections.impl.set.mutable.*;
import org.eclipse.collections.api.set.*;

import cpw.mods.fml.common.*;
import cpw.mods.fml.common.eventhandler.*;
import cpw.mods.fml.common.event.*;

public class ClassCacheTweaker implements ITweaker {
	private static final UnifiedSet<String> INCOMPATIBLE_TRANSFORMER_PREFIXES = new UnifiedSet(Arrays.asList(new String[]{"elec332.", "me.nallar.modpatcher.", "net.darkhax.", "ru.fewizz.idextender."}));//UnifiedSet 1 array 1 int, Guava 2 array 2 int // add net.darkhax. - issue in all the mods 2 modpack in 1.11.2
	private static final UnifiedSet<String> INCOMPATIBLE_TRANSFORMER_SUFFIXES = new UnifiedSet(Arrays.asList("fml.common.asm.transformers.ModAPITransformer"));

	public static ClassCache cache;
	private LaunchClassLoader classLoader;
	private File gameDir;

	@Override
	public void acceptOptions(final List<String> args, final File gameDir, final File assetsDir, final String profile) {
		this.gameDir = gameDir;
	}

	@Override
	public void injectIntoClassLoader(final LaunchClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	@Override
	public String getLaunchTarget() {
		return null;
	}

	@Override
	public String[] getLaunchArguments() {
		// have to do this by hand because i do not wish to rely on the stability of FML's configuration API
		File file = new File(new File(gameDir, "config"), "classCacheTweaker.cfg");
		file.getParentFile().mkdirs();

		Map<String, String> config = new HashMap<>();
		config.put("incompatibleTransformerPrefixes", "");
		config.put("incompatibleTransformerSuffixes", "");

		try {
			byte[] bd = new byte[(int)file.length()];
        		InputStream fis  = new BufferedInputStream(new FileInputStream(file));
        		fis.read(bd);
        		ByteArrayInputStream bis = new ByteArrayInputStream(bd);
        		BufferedReader br = new BufferedReader(new InputStreamReader(bis, "UTF-8"));
        		String line = new String(new byte[0], "UTF-8");
        		while((line = br.readLine()) != null) {
				String[] data = line.split("=", 2);
				if (data.length == 2) {
					config.put(data[0].trim(), data[1].trim());
				}
        		}
        		br.close();
        		bis.close();
        		fis.close();
		} catch (IOException e) {
			e.printStackTrace();
			cpw.mods.fml.common.FMLLog.log(org.apache.logging.log4j.Level.WARN, (Throwable)e, "ClassCacheTweaker stacktrace: %s", (Throwable)e);
		}

		try {
			StringBuilder builder = new StringBuilder();
			for (Map.Entry<String, String> entry : config.entrySet()) {
				builder.append(entry.getKey() + "=" + entry.getValue() + "\n");
			}
			byte[] bd = (builder.toString()).getBytes("UTF-8");
        		OutputStream fos0 = new FileOutputStream(file);
        		OutputStream fos = new BufferedOutputStream(fos0);
        		fos.write(bd);
        		fos.flush();
        		fos0.close();
		} catch (Exception e) {
			e.printStackTrace();
			cpw.mods.fml.common.FMLLog.log(org.apache.logging.log4j.Level.WARN, (Throwable)e, "ClassCacheTweaker stacktrace: %s", (Throwable)e);
		}

		for (String s : config.get("incompatibleTransformerPrefixes").split(";"))
			INCOMPATIBLE_TRANSFORMER_PREFIXES.add(s);
		for (String s : config.get("incompatibleTransformerSuffixes").split(";"))
			INCOMPATIBLE_TRANSFORMER_SUFFIXES.add(s);

		try {
			cache = ClassCache.load(classLoader, gameDir);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		try {
			// This is a good point in time.
			Field transformersField = LaunchClassLoader.class.getDeclaredField("transformers");
			transformersField.setAccessible(true);

			List<IClassTransformer> transformerList = (List<IClassTransformer>) transformersField.get(classLoader);

			List<IClassTransformer> capturedTransformerList = new ArrayList();
			List<IClassTransformer> preservedTransformerList = new ArrayList();

			for (IClassTransformer transformer : transformerList) {
				String className = transformer.getClass().getName();
				boolean found = false;
				for (String prefix : INCOMPATIBLE_TRANSFORMER_PREFIXES) {
					if (className.startsWith(prefix)) {
						found = true;
						break;
					}
				}
				if (!found) {
					for (String suffix : INCOMPATIBLE_TRANSFORMER_SUFFIXES) {
						if (className.endsWith(suffix)) {
							found = true;
							break;
						}
					}
				}
				if (found) {
					preservedTransformerList.add(transformer);
				} else {
					capturedTransformerList.add(transformer);
				}
			}

			ClassCacheTransformer transformer = new ClassCacheTransformer(capturedTransformerList, gameDir);

			transformerList.clear();
			transformerList.add(transformer);
			transformerList.addAll(preservedTransformerList);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return new String[0];
	}

	@Mod.EventHandler
	public void load(FMLInitializationEvent event) {
		FMLCommonHandler.instance().bus().register(cache);
	}
}
