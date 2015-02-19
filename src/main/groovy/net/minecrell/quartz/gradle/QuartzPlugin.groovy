/*
 * QuartzGradle
 * Copyright (c) 2015, Minecrell <https://github.com/Minecrell>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.minecrell.quartz.gradle

import static net.minecraftforge.gradle.common.Constants.JAR_SERVER_FRESH
import static net.minecraftforge.gradle.user.UserConstants.CONFIG_DEPS
import static net.minecraftforge.gradle.user.UserConstants.MCP_PATCH_DIR
import static net.minecraftforge.gradle.user.UserConstants.MERGE_CFG

import net.minecraftforge.gradle.delayed.DelayedFile
import net.minecraftforge.gradle.json.JsonFactory
import net.minecraftforge.gradle.json.version.Version
import net.minecraftforge.gradle.tasks.ProcessJarTask
import net.minecraftforge.gradle.user.UserBasePlugin
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet

class QuartzPlugin extends UserBasePlugin<QuartzExtension> {

    private static final String JAR_SERVER =
            '{CACHE_DIR}/minecraft/net/minecraft/minecraft_server/{MC_VERSION}/minecraft_server_filtered-{MC_VERSION}.jar'

    private static final String FML_VERSION = '1.8-8.0.27.1027'
    private static final String CACHE_DIR = "{CACHE_DIR}/minecraft/net/minecrell/quartz/$FML_VERSION"

    @Override
    protected Class<QuartzExtension> getExtensionClass() {
        QuartzExtension
    }

    @Override
    void applyPlugin() {
        super.applyPlugin()

        project.with {
            tasks.extractUserDev.doLast {
                def file = dependencyFile
                if (!file.exists()) {
                    file.createNewFile()
                    file.withOutputStream {o ->
                        QuartzPlugin.getResourceAsStream('/1.8.json').withStream {
                            o << it
                        }
                    }
                }

                loadDependencies file
            }

            tasks.mergeJars.enabled = false

            task('filterJar', type: FilterJarTask) {
                mergeCfg = delayedFile MERGE_CFG
                inJar = delayedFile JAR_SERVER_FRESH
                outJar = delayedFile JAR_SERVER
                dependsOn 'extractUserDev', 'downloadServer'
            }

            configure([tasks.deobfBinJar, tasks.deobfuscateJar]) {
                dependsOn.with {
                    remove 'mergeJars'
                    add 'filterJar'
                }
                inJar = delayedFile JAR_SERVER
            }

            task('cleanPatches', type: CleanPatchesTask) {
                patchDir = delayedFile MCP_PATCH_DIR
            }

            tasks.decompile.dependsOn 'cleanPatches'
        }
    }

    private Version version
    private boolean versionApplied

    private File getDependencyFile() {
        return new File(getDevJson().call().parentFile, '1.8.json')
    }

    private void loadDependencies(File file) {
        if (version == null) {
            file.withReader {
                version = JsonFactory.GSON.fromJson(it, Version)
            }
        }

        if (versionApplied) {
            return
        }

        if (project.configurations[CONFIG_DEPS].state == Configuration.State.UNRESOLVED) {
            version.getLibraries().each {
                def dep = project.dependencies.add(CONFIG_DEPS, it.getArtifactName()) {
                    transitive = false
                }
                if (it.artifactName.startsWith('org.ow2.asm:asm-debug-all')) {
                    project.dependencies.add('runtime', "org.ow2.asm:asm-all:$dep.version") {
                        transitive = false
                    }
                }
            }
        }

        versionApplied = true
    }

    @Override
    protected void delayedTaskConfig() {
        // Add libraries
        def file = dependencyFile
        if (file.exists()) {
            loadDependencies file
        }

        ProcessJarTask binDeobf = project.tasks.deobfBinJar
        ProcessJarTask decompDeobf = project.tasks.deobfuscateJar

        JavaPluginConvention java = project.convention.plugins['java']
        SourceSet main = java.sourceSets.main

        main.resources.files.each {
            if (it.name.endsWith('_at.cfg')) {
                project.getLogger().lifecycle("Found AccessTransformer in main resources: $it.name")
                binDeobf.addTransformer(it)
                decompDeobf.addTransformer(it)
            }
        }

        super.delayedTaskConfig()
    }

    @Override
    String getApiName() {
        'minecraft_server'
    }

    @Override
    protected String getSrcDepName() {
        'minecraft_server_src'
    }

    @Override
    protected String getBinDepName() {
        'minecraft_server_bin'
    }

    @Override
    protected boolean hasApiVersion() {
        false
    }

    @Override
    protected String getApiVersion(QuartzExtension ext) {
        null
    }

    @Override
    protected String getMcVersion(QuartzExtension ext) {
        ext.version
    }

    @Override
    protected String getApiCacheDir(QuartzExtension ext) {
        '{BUILD_DIR}/minecraft/net/minecraft/minecraft_server/{MC_VERSION}'
    }

    @Override
    protected String getSrgCacheDir(QuartzExtension userExtension) {
        "$CACHE_DIR/srgs"
    }

    @Override
    protected String getUserDevCacheDir(QuartzExtension userExtension) {
        "$CACHE_DIR/unpacked"
    }

    @Override
    protected String getUserDev() {
        "net.minecraftforge:fml:$FML_VERSION"
    }

    @Override
    protected String getClientTweaker() {
        ''
    }

    @Override
    protected String getServerTweaker() {
        project.minecraft.tweaker
    }

    @Override
    protected String getStartDir() {
        '{BUILD_DIR}/start'
    }

    @Override
    protected String getClientRunClass() {
        'net.minecraft.client.main.Main'
    }

    @Override
    protected Iterable<String> getClientRunArgs() {
        def result = ['--noCoreSearch']
        def args = project.properties['runArgs']
        if (args != null) {
            result << args
        }

        result
    }

    @Override
    protected String getServerRunClass() {
        'net.minecraft.launchwrapper.Launch'
    }

    @Override
    protected Iterable<String> getServerRunArgs() {
        getClientRunArgs()
    }

    @Override
    protected void configureDeobfuscation(ProcessJarTask processJarTask) {

    }

    @Override
    protected void doVersionChecks(String s) {

    }

    @Override
    void applyOverlayPlugin() {

    }

    @Override
    boolean canOverlayPlugin() {
        false
    }

    @Override
    protected DelayedFile getDevJson() {
        new LoadingDelayedFile(this, "$CACHE_DIR/unpacked/dev.json", {File file ->
            if (file.exists()) {
                def i = QuartzPlugin.getResourceAsStream('/empty.json')
                if (i != null) {
                    i.withStream {
                        file.withOutputStream {o ->
                            o << i
                        }
                    }
                }
            }
        })
    }

    @Override
    protected QuartzExtension getOverlayExtension() {
        null
    }

}
