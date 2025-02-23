/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;

import net.sourceforge.pmd.annotation.InternalApi;
import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.util.CollectionUtil;
import net.sourceforge.pmd.util.ResourceLoader;
import net.sourceforge.pmd.util.log.MessageReporter;
import net.sourceforge.pmd.util.log.internal.NoopReporter;

/**
 * Configurable object to load rulesets from XML resources.
 * This can be configured using a fluent API, see eg {@link #warnDeprecated(boolean)}.
 * To create a new ruleset, use {@link #loadFromResource(String)}
 * or some such overload.
 */
public final class RuleSetLoader {

    private static final Logger LOG = Logger.getLogger(RuleSetLoader.class.getName());

    private ResourceLoader resourceLoader = new ResourceLoader(RuleSetLoader.class.getClassLoader());
    private RulePriority minimumPriority = RulePriority.LOW;
    private boolean warnDeprecated = true;
    private boolean enableCompatibility = true;
    private boolean includeDeprecatedRuleReferences = false;
    private MessageReporter reporter = new NoopReporter(); // non-null

    /**
     * Create a new RuleSetLoader with a default configuration.
     * The defaults are described on each configuration method of this class.
     */
    public RuleSetLoader() { // NOPMD UnnecessaryConstructor
        // default
    }

    void setReporter(MessageReporter reporter) {
        this.reporter = reporter;
    }

    /**
     * Specify that the given classloader should be used to resolve
     * paths to external ruleset references. The default uses PMD's
     * own classpath.
     */
    public RuleSetLoader loadResourcesWith(ClassLoader classLoader) {
        this.resourceLoader = new ResourceLoader(classLoader);
        return this;
    }

    // internal
    RuleSetLoader loadResourcesWith(ResourceLoader loader) {
        this.resourceLoader = loader;
        return this;
    }

    /**
     * Filter loaded rules to only those that match or are above
     * the given priority. The default is {@link RulePriority#LOW},
     * ie, no filtering occurs.
     *
     * @return This instance, modified
     */
    public RuleSetLoader filterAbovePriority(RulePriority minimumPriority) {
        this.minimumPriority = minimumPriority;
        return this;
    }

    /**
     * Log a warning when referencing a deprecated rule.
     * This is enabled by default.
     *
     * @return This instance, modified
     */
    public RuleSetLoader warnDeprecated(boolean warn) {
        this.warnDeprecated = warn;
        return this;
    }

    /**
     * Enable translating old rule references to newer ones, if they have
     * been moved or renamed. This is enabled by default, if disabled,
     * unresolved references will not be translated and will produce an
     * error.
     *
     * @return This instance, modified
     */
    public RuleSetLoader enableCompatibility(boolean enable) {
        this.enableCompatibility = enable;
        return this;
    }

    /**
     * Follow deprecated rule references. By default this is off,
     * and those references will be ignored (with a warning depending
     * on {@link #enableCompatibility(boolean)}).
     *
     * @return This instance, modified
     */
    public RuleSetLoader includeDeprecatedRuleReferences(boolean enable) {
        this.includeDeprecatedRuleReferences = enable;
        return this;
    }

    /**
     * Create a new rule set factory, if you have to (that class is deprecated).
     * That factory will use the configuration that was set using the setters of this.
     *
     * @deprecated {@link RuleSetFactory} is deprecated, replace its usages
     *     with usages of this class, or of static factory methods of {@link RuleSet}
     */
    @Deprecated
    public RuleSetFactory toFactory() {
        return new RuleSetFactory(
            this.resourceLoader,
            this.minimumPriority,
            this.warnDeprecated,
            this.enableCompatibility,
            this.includeDeprecatedRuleReferences
        );
    }


    /**
     * Parses and returns a ruleset from its location. The location may
     * be a file system path, or a resource path (see {@link #loadResourcesWith(ClassLoader)}).
     *
     * <p>This replaces {@link RuleSetFactory#createRuleSet(String)},
     * but does not split commas.
     *
     * @param rulesetPath A reference to a single ruleset
     *
     * @throws RuleSetLoadException If any error occurs (eg, invalid syntax, or resource not found)
     */
    public RuleSet loadFromResource(String rulesetPath) {
        return loadFromResource(new RuleSetReferenceId(rulesetPath));
    }

    /**
     * Parses and returns a ruleset from string content.
     *
     * @param filename          The symbolic "file name", for error messages.
     * @param rulesetXmlContent Xml file contents
     *
     * @throws RuleSetLoadException If any error occurs (eg, invalid syntax)
     */
    public RuleSet loadFromString(String filename, final String rulesetXmlContent) {
        return loadFromResource(new RuleSetReferenceId(filename) {
            @Override
            public InputStream getInputStream(ResourceLoader rl) {
                return new ByteArrayInputStream(rulesetXmlContent.getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    /**
     * Parses several resources into a list of rulesets.
     *
     * @param paths Paths
     *
     * @throws RuleSetLoadException If any error occurs (eg, invalid syntax, or resource not found),
     *                              for any of the parameters
     * @throws NullPointerException If the parameter, or any component is null
     */
    public List<RuleSet> loadFromResources(Collection<String> paths) {
        List<RuleSet> ruleSets = new ArrayList<>(paths.size());
        for (String path : paths) {
            ruleSets.add(loadFromResource(path));
        }
        return ruleSets;
    }

    /**
     * Loads a list of rulesets, if any has an error, report it on the contextual
     * error reporter instead of aborting, and continue loading the rest.
     *
     * <p>Internal API: might be published later, or maybe in PMD 7 this
     * will be the default behaviour of every method of this class.
     */
    @InternalApi
    public List<RuleSet> loadRuleSetsWithoutException(List<String> rulesetPaths) {
        List<RuleSet> ruleSets = new ArrayList<>(rulesetPaths.size());
        boolean anyRules = false;
        for (String path : rulesetPaths) {
            try {
                RuleSet ruleset = this.loadFromResource(path);
                anyRules |= !ruleset.getRules().isEmpty();
                printRulesInDebug(path, ruleset);
                ruleSets.add(ruleset);
            } catch (RuleSetLoadException e) {
                if (e.getCause() != null) {
                    // eg RuleSetNotFoundException
                    reporter.errorEx("Cannot load ruleset {0}", new Object[] { path }, e.getCause());
                } else {
                    reporter.errorEx("Cannot load ruleset {0}", new Object[] { path }, e);
                }
            }
        }
        if (!anyRules) {
            reporter.warn("No rules found. Maybe you misspelled a rule name? ({0})",
                          StringUtils.join(rulesetPaths, ','));
        }
        return ruleSets;
    }

    void printRulesInDebug(String path, RuleSet ruleset) {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(MessageFormat.format("Rules loaded from {0}:", path));
            for (Rule rule : ruleset.getRules()) {
                LOG.fine(MessageFormat.format("- {0} ({1})", rule.getName(), rule.getLanguage().getName()));
            }
        }
        if (ruleset.getRules().isEmpty()) {
            reporter.warn("No rules found in ruleset {0}", path);
        }

    }

    /**
     * Parses several resources into a list of rulesets.
     *
     * @param first First path
     * @param rest  Paths
     *
     * @throws RuleSetLoadException If any error occurs (eg, invalid syntax, or resource not found),
     *                              for any of the parameters
     * @throws NullPointerException If the parameter, or any component is null
     */
    public List<RuleSet> loadFromResources(String first, String... rest) {
        return loadFromResources(CollectionUtil.listOf(first, rest));
    }

    // package private
    RuleSet loadFromResource(RuleSetReferenceId ruleSetReferenceId) {
        try {
            return toFactory().createRuleSet(ruleSetReferenceId);
        } catch (Exception e) {
            throw new RuleSetLoadException("Cannot parse " + ruleSetReferenceId, e);
        }
    }


    /**
     * Configure a new ruleset factory builder according to the parameters
     * of the given PMD configuration.
     */
    public static RuleSetLoader fromPmdConfig(PMDConfiguration configuration) {
        return new RuleSetLoader().filterAbovePriority(configuration.getMinimumPriority())
                                  .enableCompatibility(configuration.isRuleSetFactoryCompatibilityEnabled());
    }


    /**
     * Returns an Iterator of RuleSet objects loaded from descriptions from the
     * "categories.properties" resource for each language. This
     * uses the classpath of the resource loader ({@link #loadResourcesWith(ClassLoader)}).
     *
     * @return A list of all category rulesets
     *
     * @throws RuleSetLoadException If a standard ruleset cannot be loaded.
     *                              This is a corner case, that probably should not be caught by clients.
     *                              The standard rulesets are well-formed, at least in stock PMD distributions.
     *
     */
    public List<RuleSet> getStandardRuleSets() {
        String rulesetsProperties;
        List<RuleSetReferenceId> ruleSetReferenceIds = new ArrayList<>();
        for (Language language : LanguageRegistry.findWithRuleSupport()) {
            Properties props = new Properties();
            rulesetsProperties = "category/" + language.getTerseName() + "/categories.properties";
            try (InputStream inputStream = resourceLoader.loadClassPathResourceAsStreamOrThrow(rulesetsProperties)) {
                props.load(inputStream);
                String rulesetFilenames = props.getProperty("rulesets.filenames");
                if (rulesetFilenames != null) {
                    ruleSetReferenceIds.addAll(RuleSetReferenceId.parse(rulesetFilenames));
                }
            } catch (RuleSetNotFoundException e) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine("The language " + language.getTerseName() + " provides no " + rulesetsProperties + ".");
                }
            } catch (IOException ioe) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine("Couldn't read " + rulesetsProperties
                                 + "; please ensure that the directory is on the classpath. The current classpath is: "
                                 + System.getProperty("java.class.path"));
                    LOG.fine(ioe.toString());
                }
            }
        }

        List<RuleSet> ruleSets = new ArrayList<>();
        for (RuleSetReferenceId id : ruleSetReferenceIds) {
            ruleSets.add(loadFromResource(id)); // may throw
        }
        return ruleSets;
    }
}
