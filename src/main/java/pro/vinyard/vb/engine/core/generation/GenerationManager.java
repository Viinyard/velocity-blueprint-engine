package pro.vinyard.vb.engine.core.generation;

import org.apache.commons.io.FileUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.tools.generic.DateTool;
import org.apache.velocity.tools.generic.MathTool;
import org.pf4j.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import pro.vinyard.vb.engine.core.environment.EnvironmentManager;
import pro.vinyard.vb.engine.core.exception.VelocityBlueprintException;
import pro.vinyard.vb.engine.core.model.ModelManager;
import pro.vinyard.vb.engine.core.model.entities.*;
import pro.vinyard.vb.engine.core.pluginManager.VbPluginManager;

import java.io.*;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

@Component
public class GenerationManager {

    Logger logger = LoggerFactory.getLogger(GenerationManager.class);

    @Autowired
    private ModelManager modelManager;

    @Autowired
    private VbPluginManager vbPluginManager;

    @Autowired
    private EnvironmentManager environmentManager;

    public void generate(String modelName, BiFunction<PromptType, VelocityContext, Object> promptProcessor) throws VelocityBlueprintException, IOException {
        this.modelManager.checkModel(modelName);

        VelocityContext velocityContext = new VelocityContext();
        velocityContext.put("date", new DateTool());
        velocityContext.put("math", new MathTool());

        Model model = this.modelManager.loadModel(velocityContext, modelName);

        PluginManager pluginManager = vbPluginManager.initPlugins(velocityContext);

        Optional.of(model).map(Model::getProperties).map(Properties::getPropertyList).orElseGet(Collections::emptyList).stream()
                .peek(p -> logger.debug("Put property on velocity context {} : {}", p.getKey(), p.getValue()))
                .forEach(p -> velocityContext.put(p.getKey(), p.getValue()));

        Stream.of(
                Optional.of(model).map(Model::getPrompts).map(Prompts::getMultiSelectList).orElseGet(Collections::emptyList),
                Optional.of(model).map(Model::getPrompts).map(Prompts::getMonoSelectList).orElseGet(Collections::emptyList),
                Optional.of(model).map(Model::getPrompts).map(Prompts::getStringInputList).orElseGet(Collections::emptyList)
        ).flatMap(List::stream).map(PromptType.class::cast).sorted().forEach(s -> {
            Object value = promptProcessor.apply(s, velocityContext);
            logger.debug("Put prompt on velocity context {} : {}", s.getValue(), value);
            velocityContext.put(s.getValue(), value);
        });

        model.getDirectives().getDirectiveList().forEach(d -> {
            try {
                this.processDirective(velocityContext, d);
            } catch (Exception e) {
                throw new RuntimeException("Cannot process template.", e);
            }
        });

        vbPluginManager.unloadPlugins(pluginManager);
    }

    public void processDirective(VelocityContext velocityContext, Directive directive) {
        Function<String, String> templated = s -> this.processTemplate(velocityContext, s);
        File template = new File(environmentManager.getTemplateDirectory(), Optional.of(directive).map(Directive::getTemplate).map(templated).map(String::trim).orElse(""));
        File file = new File(environmentManager.getHomeDirectory(), Optional.of(directive).map(Directive::getValue).map(templated).map(String::trim).orElse(""));
        this.processTemplate(velocityContext, template, file);
    }

    public void processTemplate(VelocityContext velocityContext, File template, File file) {
        Velocity.init();

        try {
            FileUtils.forceMkdirParent(file);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create parent directory.", e);
        }

        try (FileWriter fileWriter = new FileWriter(file)) {
            Velocity.evaluate(velocityContext, fileWriter, "LOG", new FileReader(template));
            logger.info("File {} generated.", file.getAbsolutePath());
            fileWriter.flush();
        } catch (IOException e) {
            throw new RuntimeException("Cannot process template.", e);
        }
    }

    public String processTemplate(VelocityContext velocityContext, File template) throws IOException {
        StringWriter stringWriter = new StringWriter();
        Velocity.evaluate(velocityContext, stringWriter, "LOG", new FileReader(template));
        return stringWriter.toString();
    }

    public String processTemplate(VelocityContext velocityContext, String template) {
        StringWriter stringWriter = new StringWriter();
        Velocity.evaluate(velocityContext, stringWriter, "LOG", new StringReader(template));
        return stringWriter.toString();
    }
}
