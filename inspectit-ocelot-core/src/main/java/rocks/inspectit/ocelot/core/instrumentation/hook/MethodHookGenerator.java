package rocks.inspectit.ocelot.core.instrumentation.hook;

import com.google.common.annotations.VisibleForTesting;
import io.opencensus.trace.Sampler;
import io.opencensus.trace.samplers.Samplers;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.bytebuddy.description.method.MethodDescription;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import rocks.inspectit.ocelot.config.model.instrumentation.rules.MetricRecordingSettings;
import rocks.inspectit.ocelot.config.model.instrumentation.rules.RuleTracingSettings;
import rocks.inspectit.ocelot.core.instrumentation.autotracing.StackTraceSampler;
import rocks.inspectit.ocelot.core.instrumentation.config.model.ActionCallConfig;
import rocks.inspectit.ocelot.core.instrumentation.config.model.MethodHookConfiguration;
import rocks.inspectit.ocelot.core.instrumentation.context.ContextManager;
import rocks.inspectit.ocelot.core.instrumentation.hook.actions.ConditionalHookAction;
import rocks.inspectit.ocelot.core.instrumentation.hook.actions.IHookAction;
import rocks.inspectit.ocelot.core.instrumentation.hook.actions.MetricsRecorder;
import rocks.inspectit.ocelot.core.instrumentation.hook.actions.model.MetricAccessor;
import rocks.inspectit.ocelot.core.instrumentation.hook.actions.span.*;
import rocks.inspectit.ocelot.core.instrumentation.hook.tags.CommonTagsToAttributesManager;
import rocks.inspectit.ocelot.core.metrics.MeasuresAndViewsManager;
import rocks.inspectit.ocelot.core.privacy.obfuscation.ObfuscationManager;
import rocks.inspectit.ocelot.core.selfmonitoring.ActionScopeFactory;
import rocks.inspectit.ocelot.core.tags.CommonTagsManager;

import java.util.*;
import java.util.stream.Collectors;

import static java.lang.Boolean.TRUE;

/**
 * This class is responsible for translating {@link MethodHookConfiguration}s
 * into executable {@link MethodHook}s.
 */
@Component
@Slf4j
public class MethodHookGenerator {

    @Autowired
    private ContextManager contextManager;

    @Autowired
    private CommonTagsManager commonTagsManager;

    @Autowired
    private MeasuresAndViewsManager metricsManager;

    @Autowired
    private ActionCallGenerator actionCallGenerator;

    @Autowired
    private VariableAccessorFactory variableAccessorFactory;

    @Autowired
    private ObfuscationManager obfuscationManager;

    @Autowired
    private CommonTagsToAttributesManager commonTagsToAttributesManager;

    @Autowired
    private StackTraceSampler stackTraceSampler;

    @Autowired
    private ActionScopeFactory actionScopeFactory;

    /**
     * Builds an executable method hook based on the given configuration.
     *
     * @param declaringClass the class defining the method which is being hooked
     * @param method         a method descriptor of the hooked method
     * @param config         the configuration to use for building the hook
     *
     * @return the generated method hook
     */
    public MethodHook buildHook(Class<?> declaringClass, MethodDescription method, MethodHookConfiguration config) {
        MethodHook.MethodHookBuilder builder = MethodHook.builder()
                .inspectitContextManager(contextManager)
                .sourceConfiguration(config);

        MethodReflectionInformation methodInfo = MethodReflectionInformation.createFor(declaringClass, method);
        builder.methodInformation(methodInfo);

        RuleTracingSettings tracingSettings = config.getTracing();

        builder.entryActions(buildActionCalls(config.getPreEntryActions(), methodInfo));
        builder.entryActions(buildActionCalls(config.getEntryActions(), methodInfo));
        if (tracingSettings != null) {
            builder.entryActions(buildTracingEntryActions(tracingSettings));
        }
        builder.entryActions(buildActionCalls(config.getPostEntryActions(), methodInfo));

        builder.exitActions(buildActionCalls(config.getPreExitActions(), methodInfo));
        builder.exitActions(buildActionCalls(config.getExitActions(), methodInfo));
        if (tracingSettings != null) {
            builder.exitActions(buildTracingExitActions(tracingSettings));
        }
        buildMetricsRecorder(config).ifPresent(builder::exitAction);
        builder.exitActions(buildActionCalls(config.getPostExitActions(), methodInfo));

        builder.actionScopeFactory(actionScopeFactory);

        return builder.build();
    }

    private List<IHookAction> buildTracingEntryActions(RuleTracingSettings tracing) {
        if (TRUE.equals(tracing.getStartSpan()) || tracing.getContinueSpan() != null) {

            val actionBuilder = ContinueOrStartSpanAction.builder();
            actionBuilder.commonTagsToAttributesManager(commonTagsToAttributesManager);

            if (TRUE.equals(tracing.getStartSpan())) {
                VariableAccessor name = Optional.ofNullable(tracing.getName())
                        .map(variableAccessorFactory::getVariableAccessor)
                        .orElse(null);
                actionBuilder.startSpanCondition(ConditionalHookAction.getAsPredicate(tracing.getStartSpanConditions(), variableAccessorFactory))
                        .nameAccessor(name)
                        .spanKind(tracing.getKind());
                configureSampling(tracing, actionBuilder);
            } else {
                actionBuilder.startSpanCondition(ctx -> false);
            }

            if (tracing.getContinueSpan() != null) {
                actionBuilder.continueSpanCondition(ConditionalHookAction.getAsPredicate(tracing.getContinueSpanConditions(), variableAccessorFactory))
                        .continueSpanDataKey(tracing.getContinueSpan());
            } else {
                actionBuilder.continueSpanCondition(ctx -> false);
            }
            configureAutoTracing(tracing, actionBuilder);
            actionBuilder.stackTraceSampler(stackTraceSampler);

            val result = new ArrayList<IHookAction>();
            result.add(actionBuilder.build());

            if (tracing.getStoreSpan() != null) {
                result.add(new StoreSpanAction(tracing.getStoreSpan()));
            }
            return result;
        } else {
            return Collections.emptyList();
        }
    }

    private void configureAutoTracing(RuleTracingSettings tracing, ContinueOrStartSpanAction.ContinueOrStartSpanActionBuilder actionBuilder) {
        if (tracing.getAutoTracing() != null) {
            if (TRUE.equals(tracing.getAutoTracing())) {
                actionBuilder.autoTrace(StackTraceSampler.Mode.ENABLE);
            } else {
                actionBuilder.autoTrace(StackTraceSampler.Mode.DISABLE);
            }
        } else {
            actionBuilder.autoTrace(StackTraceSampler.Mode.KEEP);
        }
    }

    private void configureSampling(RuleTracingSettings tracing, ContinueOrStartSpanAction.ContinueOrStartSpanActionBuilder actionBuilder) {
        String sampleProbability = tracing.getSampleProbability();
        if (!StringUtils.isBlank(sampleProbability)) {
            try {
                double constantProbability = Double.parseDouble(sampleProbability);
                Sampler sampler = Samplers.probabilitySampler(Math.max(0.0, Math.min(1.0, constantProbability)));
                actionBuilder.staticSampler(sampler);
            } catch (NumberFormatException e) {
                VariableAccessor probabilityAccessor = variableAccessorFactory.getVariableAccessor(sampleProbability);
                actionBuilder.dynamicSampleProbabilityAccessor(probabilityAccessor);
            }
        }
    }

    @VisibleForTesting
    List<IHookAction> buildTracingExitActions(RuleTracingSettings tracing) {
        val result = new ArrayList<IHookAction>();

        boolean isSpanStartedOrContinued = tracing.getStartSpan() || StringUtils.isNotBlank(tracing.getContinueSpan());
        if (isSpanStartedOrContinued) {

            if (StringUtils.isNotBlank(tracing.getErrorStatus())) {
                VariableAccessor accessor = variableAccessorFactory.getVariableAccessor(tracing.getErrorStatus());
                result.add(new SetSpanStatusAction(accessor));
            }

            val attributes = tracing.getAttributes();
            if (!attributes.isEmpty()) {
                Map<String, VariableAccessor> attributeAccessors = new HashMap<>();
                attributes.forEach((attribute, variable) -> attributeAccessors.put(attribute, variableAccessorFactory.getVariableAccessor(variable)));
                IHookAction endTraceAction = new WriteSpanAttributesAction(attributeAccessors, obfuscationManager.obfuscatorySupplier());
                IHookAction actionWithConditions = ConditionalHookAction.wrapWithConditionChecks(tracing.getAttributeConditions(), endTraceAction, variableAccessorFactory);
                result.add(actionWithConditions);
            }

            if (TRUE.equals(tracing.getEndSpan())) {
                val endSpanAction = new EndSpanAction(ConditionalHookAction.getAsPredicate(tracing.getEndSpanConditions(), variableAccessorFactory));
                result.add(endSpanAction);
            }
        }

        return result;
    }

    private Optional<IHookAction> buildMetricsRecorder(MethodHookConfiguration config) {
        Collection<MetricRecordingSettings> metricRecordingSettings = config.getMetrics();
        if (!metricRecordingSettings.isEmpty()) {
            List<MetricAccessor> metricAccessors = metricRecordingSettings.stream()
                    .map(this::buildMetricAccessor)
                    .collect(Collectors.toList());

            MetricsRecorder recorder = new MetricsRecorder(metricAccessors, commonTagsManager, metricsManager);
            return Optional.of(recorder);
        } else {
            return Optional.empty();
        }
    }

    @VisibleForTesting
    MetricAccessor buildMetricAccessor(MetricRecordingSettings metricSettings) {
        String value = metricSettings.getValue();
        VariableAccessor valueAccessor;
        try {
            valueAccessor = variableAccessorFactory.getConstantAccessor(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            valueAccessor = variableAccessorFactory.getVariableAccessor(value);
        }

        Map<String, VariableAccessor> tagAccessors = metricSettings.getDataTags()
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> variableAccessorFactory.getVariableAccessor(entry.getValue())));

        return new MetricAccessor(metricSettings.getMetric(), valueAccessor, metricSettings.getConstantTags(), tagAccessors);
    }

    private List<IHookAction> buildActionCalls(List<ActionCallConfig> calls, MethodReflectionInformation methodInfo) {

        List<IHookAction> result = new ArrayList<>();
        for (val call : calls) {
            try {
                result.add(actionCallGenerator.generateAndBindGenericAction(methodInfo, call));
            } catch (Exception e) {
                log.error("Failed to build action {} for data {} on method {}, no value will be assigned", call.getAction()
                        .getName(), call.getName(), methodInfo.getMethodFQN(), e);
            }
        }
        return result;
    }

}
