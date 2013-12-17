package brooklyn.enricher;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import brooklyn.enricher.basic.Aggregator;
import brooklyn.enricher.basic.Combinor;
import brooklyn.enricher.basic.Propagator;
import brooklyn.enricher.basic.Transformer;
import brooklyn.entity.Entity;
import brooklyn.event.AttributeSensor;
import brooklyn.event.Sensor;
import brooklyn.policy.EnricherSpec;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.flags.TypeCoercions;
import brooklyn.util.text.Strings;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;

public class Enrichers {

    private Enrichers() {}
    
    public static InitialBuilder<?> builder() {
        return new ConcreteInitialBuilder();
    }

    public abstract static class Builder<B extends Builder<B>> {
        @SuppressWarnings("unchecked")
        protected B self() {
           return (B) this;
        }
    }
    
    public abstract static class InitialBuilder<B extends InitialBuilder<B>> extends Builder<B> {
        public PropagatorBuilder<?> propagating(Map<? extends Sensor<?>, ? extends Sensor<?>> vals) {
            return new ConcretePropagatorBuilder(vals);
        }
        public PropagatorBuilder<?> propagating(Iterable<? extends Sensor<?>> vals) {
            return new ConcretePropagatorBuilder(vals);
        }
        public PropagatorBuilder<?> propagating(Sensor<?>... vals) {
            return new ConcretePropagatorBuilder(vals);
        }
        public PropagatorBuilder<?> propagatingAll() {
            return new ConcretePropagatorBuilder(true, null);
        }
        public PropagatorBuilder<?> propagatingAllBut(Sensor<?>... vals) {
            return new ConcretePropagatorBuilder(true, ImmutableSet.copyOf(vals));
        }
        public PropagatorBuilder<?> propagatingAllBut(Iterable<? extends Sensor<?>> vals) {
            return new ConcretePropagatorBuilder(true, vals);
        }
        public <S> TransformerBuilder<S, Object, ?> transforming(AttributeSensor<S> val) {
            return new ConcreteTransformerBuilder<S, Object>(val);
        }
        public <S> CombinorBuilder<S, Object, ?> combining(AttributeSensor<? extends S>... vals) {
            return new ConcreteCombinorBuilder<S, Object>(vals);
        }
        public <S> CombinorBuilder<S, Object, ?> combining(Collection<AttributeSensor<? extends S>> vals) {
            return new ConcreteCombinorBuilder<S, Object>(vals);
        }
        public <S> AggregatorBuilder<S, Object, ?> aggregating(AttributeSensor<S> val) {
            return new ConcreteAggregatorBuilder<S,Object>(val);
        }
    }


    public abstract static class AggregatorBuilder<S, T, B extends AggregatorBuilder<S, T, B>> extends Builder<B> {
        protected final AttributeSensor<S> aggregating;
        protected AttributeSensor<T> publishing;
        protected Entity fromEntity;
        protected Function<? super Collection<S>, ? extends T> computing;
        protected Boolean fromMembers;
        protected Boolean fromChildren;
        protected Boolean excludingBlank;
        protected ImmutableSet<Entity> fromHardcodedProducers;
        protected Predicate<? super Entity> entityFilter;
        protected Predicate<Object> valueFilter;
        protected Object defaultValueForUnreportedSensors;
        protected Object valueToReportIfNoSensors;
        
        public AggregatorBuilder(AttributeSensor<S> aggregating) {
            this.aggregating = aggregating;
        }
        public <T2 extends T, B2 extends AggregatorBuilder<S, T2, B2>> AggregatorBuilder<S, T2, B2> publishing(AttributeSensor<T2> val) {
            this.publishing = (AttributeSensor) checkNotNull(val);
            return (AggregatorBuilder<S, T2, B2>) self();
        }
        public B from(Entity val) {
            this.fromEntity = checkNotNull(val);
            return self();
        }
        public B fromMembers() {
            this.fromMembers = true;
            return self();
        }
        public B fromChildren() {
            this.fromChildren = true;
            return self();
        }
        public B fromHardcodedProducers(Iterable<? extends Entity> val) {
            this.fromHardcodedProducers = ImmutableSet.copyOf(val);
            return self();
        }
        public B computing(Function<? super Collection<S>, ? extends T> val) {
            this.computing = checkNotNull(val);
            return self();
        }
        public B computingSum() {
            // relies of TypeCoercion of result from Number to T, and type erasure for us to get away with it!
            Function<Collection<S>, Number> function = new Function<Collection<S>, Number>()  {
                @Override public Number apply(Collection<S> input) {
                    return sum((Collection)input, (Number)defaultValueForUnreportedSensors, (Number)valueToReportIfNoSensors, (TypeToken) publishing.getTypeToken());
                }};
            this.computing((Function)function);
            return self();
        }
        public B computingAverage() {
            // relies of TypeCoercion of result from Number to T, and type erasure for us to get away with it!
            Function<Collection<S>, Number> function = new Function<Collection<S>, Number>() {
                @Override public Number apply(Collection<S> input) {
                    return average((Collection)input, (Number)defaultValueForUnreportedSensors, (Number)valueToReportIfNoSensors, (TypeToken) publishing.getTypeToken());
                }};
            this.computing((Function)function);
            return self();
        }
        public B defaultValueForUnreportedSensors(S val) {
            this.defaultValueForUnreportedSensors = val;
            return self();
        }
        public B valueToReportIfNoSensors(Object val) {
            this.valueToReportIfNoSensors = val;
            return self();
        }
        public B entityFilter(Predicate<? super Entity> val) {
            this.entityFilter = val;
            return self();
        }
        public B excludingBlank() {
            this.excludingBlank = true;
            return self();
        }
        public EnricherSpec<?> build() {
            Predicate<Object> valueFilter;
            if (Boolean.TRUE.equals(excludingBlank)) {
                valueFilter = new Predicate<Object>() {
                    @Override public boolean apply(Object input) {
                        return (input != null) &&
                                ((input instanceof CharSequence) ? Strings.isNonBlank((CharSequence)input) : true);
                    }
                };
            } else {
                valueFilter = null;
            }
            // FIXME excludingBlank; use valueFilter? exclude means ignored entirely or substituted for defaultMemberValue?
            return EnricherSpec.create(Aggregator.class)
                    .configure(MutableMap.builder()
                            .putIfNotNull(Aggregator.PRODUCER, fromEntity)
                            .put(Aggregator.TARGET_SENSOR, publishing)
                            .put(Aggregator.SOURCE_SENSOR, aggregating)
                            .putIfNotNull(Aggregator.FROM_CHILDREN, fromChildren)
                            .putIfNotNull(Aggregator.FROM_MEMBERS, fromMembers)
                            .putIfNotNull(Aggregator.TRANSFORMATION, computing)
                            .putIfNotNull(Aggregator.FROM_HARDCODED_PRODUCERS, fromHardcodedProducers)
                            .putIfNotNull(Aggregator.ENTITY_FILTER, entityFilter)
                            .putIfNotNull(Aggregator.VALUE_FILTER, valueFilter)
                            .putIfNotNull(Aggregator.DEFAULT_MEMBER_VALUE, defaultValueForUnreportedSensors)
                            .build());
        }
        
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .omitNullValues()
                    .add("aggregating", aggregating)
                    .add("publishing", publishing)
                    .add("fromEntity", fromEntity)
                    .add("computing", computing)
                    .add("fromMembers", fromMembers)
                    .add("fromChildren", fromChildren)
                    .add("excludingBlank", excludingBlank)
                    .add("fromHardcodedProducers", fromHardcodedProducers)
                    .add("entityFilter", entityFilter)
                    .add("valueFilter", valueFilter)
                    .add("defaultValueForUnreportedSensors", defaultValueForUnreportedSensors)
                    .add("valueToReportIfNoSensors", valueToReportIfNoSensors)
                    .toString();
        }
    }
    
    public abstract static class CombinorBuilder<S, T, B extends CombinorBuilder<S, T, B>> extends Builder<B> {
        protected final List<AttributeSensor<? extends S>> combining;
        protected AttributeSensor<T> publishing;
        protected Entity fromEntity;
        protected Function<? super Collection<S>, ? extends T> computing;
        protected Boolean excludingBlank;
        protected Object valueToReportIfNoSensors;
        protected Predicate<Object> valueFilter;

        // For summing/averaging
        protected Object defaultValueForUnreportedSensors;
        
        public CombinorBuilder(AttributeSensor<? extends S>... vals) {
            this(ImmutableList.copyOf(vals));
        }
        public CombinorBuilder(Collection<AttributeSensor<? extends S>> vals) {
            checkArgument(checkNotNull(vals).size() > 0, "combining-sensors must be non-empty");
            this.combining = ImmutableList.<AttributeSensor<? extends S>>copyOf(vals);
        }
        public <T2 extends T, B2 extends CombinorBuilder<S, T2, B2>> CombinorBuilder<S, T2, B2> publishing(AttributeSensor<T2> val) {
            this.publishing = (AttributeSensor) checkNotNull(val);
            return (CombinorBuilder<S, T2, B2>) self();
        }
        public B from(Entity val) {
            this.fromEntity = checkNotNull(val);
            return self();
        }
        public B computing(Function<? super Collection<S>, ? extends T> val) {
            this.computing = checkNotNull(val);
            return self();
        }
        public B computingSum() {
            Function<Collection<S>, Number> function = new Function<Collection<S>, Number>() {
                @Override public Number apply(Collection<S> input) {
                    return sum((Collection)input, (Number)defaultValueForUnreportedSensors, (Number)valueToReportIfNoSensors, (TypeToken) publishing.getTypeToken());
                }};
            this.computing((Function)function);
            return self();
        }
        public B computingAverage() {
            Function<Collection<S>, Number> function = new Function<Collection<S>, Number>() {
                @Override public Number apply(Collection<S> input) {
                    return average((Collection)input, (Number)defaultValueForUnreportedSensors, (Number)valueToReportIfNoSensors, (TypeToken) publishing.getTypeToken());
                }};
            this.computing((Function)function);
            return self();
        }
        public B defaultValueForUnreportedSensors(Object val) {
            this.defaultValueForUnreportedSensors = val;
            return self();
        }
        public B valueToReportIfNoSensors(Object val) {
            this.valueToReportIfNoSensors = val;
            return self();
        }
        public B excludingBlank() {
            this.excludingBlank = true;
            return self();
        }
        public EnricherSpec<?> build() {
            return EnricherSpec.create(Combinor.class)
                    .configure(MutableMap.builder()
                            .putIfNotNull(Combinor.PRODUCER, fromEntity)
                            .put(Combinor.TARGET_SENSOR, publishing)
                            .put(Combinor.SOURCE_SENSORS, combining)
                            .putIfNotNull(Combinor.TRANSFORMATION, computing)
                            .putIfNotNull(Combinor.VALUE_FILTER, valueFilter)
                            .build());
        }
        
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .omitNullValues()
                    .add("combining", combining)
                    .add("publishing", publishing)
                    .add("fromEntity", fromEntity)
                    .add("computing", computing)
                    .add("excludingBlank", excludingBlank)
                    .add("valueToReportIfNoSensors", valueToReportIfNoSensors)
                    .add("valueFilter", valueFilter)
                    .toString();
        }
    }

    public abstract static class TransformerBuilder<S, T, B extends TransformerBuilder<S, T, B>> extends Builder<B> {
        protected final AttributeSensor<S> transforming;
        protected AttributeSensor<T> publishing;
        protected Entity fromEntity;
        protected Function<? super S, ?> computing;

        public TransformerBuilder(AttributeSensor<S> val) {
            this.transforming = checkNotNull(val);
        }
        public <T2 extends T, B2 extends TransformerBuilder<S, T2, B2>> TransformerBuilder<S, T2, B2> publishing(AttributeSensor<T2> val) {
            this.publishing = (AttributeSensor) checkNotNull(val);
            return (TransformerBuilder<S, T2, B2>) self();
        }
        public B from(Entity val) {
            this.fromEntity = checkNotNull(val);
            return self();
        }
        public B computing(Function<? super S, ? extends T> val) {
            this.computing = checkNotNull(val);
            return self();
        }
        public EnricherSpec<?> build() {
            return EnricherSpec.create(Transformer.class)
                    .configure(MutableMap.builder()
                            .putIfNotNull(Transformer.PRODUCER, fromEntity)
                            .put(Transformer.TARGET_SENSOR, publishing)
                            .put(Transformer.SOURCE_SENSOR, transforming)
                            .putIfNotNull(Transformer.TRANSFORMATION, computing)
                            .build());
        }
        
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .omitNullValues()
                    .add("publishing", publishing)
                    .add("transforming", transforming)
                    .add("fromEntity", fromEntity)
                    .add("computing", computing)
                    .toString();
        }
    }

    public abstract static class PropagatorBuilder<B extends PropagatorBuilder<B>> extends Builder<B> {
        protected final Map<? extends Sensor<?>, ? extends Sensor<?>> propagating;
        protected final Boolean propagatingAll;
        protected final Iterable<? extends Sensor<?>> propagatingAllBut;
        protected Entity fromEntity;
        
        public PropagatorBuilder(Map<? extends Sensor<?>, ? extends Sensor<?>> vals) {
            checkArgument(checkNotNull(vals).size() > 0, "propagating-sensors must be non-empty");
            this.propagating = vals;
            this.propagatingAll = null;
            this.propagatingAllBut = null;
        }
        public PropagatorBuilder(Iterable<? extends Sensor<?>> vals) {
            this(newIdentityMap(ImmutableSet.copyOf(vals)));
        }
        public PropagatorBuilder(Sensor<?>... vals) {
            this(newIdentityMap(ImmutableSet.copyOf(vals)));
        }
        public PropagatorBuilder(boolean propagatingAll, Iterable<? extends Sensor<?>> butVals) {
            // Ugly constructor! Taking boolean to differentiate it from others; could use a static builder
            // but feels like overkill having a builder for a builder, being called by a builder!
            checkArgument(propagatingAll, "Not propagating all; use PropagatingAll(vals)");
            this.propagating = null;
            this.propagatingAll = true;
            this.propagatingAllBut = (butVals == null || Iterables.isEmpty(butVals)) ? null: butVals;
        }
        public B from(Entity val) {
            this.fromEntity = checkNotNull(val);
            return self();
        }
        public EnricherSpec<?> build() {
            return EnricherSpec.create(Propagator.class)
                    .configure(MutableMap.builder()
                            .putIfNotNull(Propagator.PRODUCER, fromEntity)
                            .putIfNotNull(Propagator.SENSOR_MAPPING, propagating)
                            .putIfNotNull(Propagator.PROPAGATING_ALL, propagatingAll)
                            .putIfNotNull(Propagator.PROPAGATING_ALL_BUT, propagatingAllBut)
                            .build());
        }
        
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .omitNullValues()
                    .add("fromEntity", fromEntity)
                    .add("propagating", propagating)
                    .add("propagatingAll", propagatingAll)
                    .add("propagatingAllBut", propagatingAllBut)
                    .toString();
        }
    }

    private static class ConcreteInitialBuilder extends InitialBuilder<ConcreteInitialBuilder> {
    }

    private static class ConcreteAggregatorBuilder<S, T> extends AggregatorBuilder<S, T, ConcreteAggregatorBuilder<S, T>> {
        public ConcreteAggregatorBuilder(AttributeSensor<S> aggregating) {
            super(aggregating);
        }
    }

    private static class ConcretePropagatorBuilder extends PropagatorBuilder<ConcretePropagatorBuilder> {
        public ConcretePropagatorBuilder(Map<? extends Sensor<?>, ? extends Sensor<?>> vals) {
            super(vals);
        }
        public ConcretePropagatorBuilder(Iterable<? extends Sensor<?>> vals) {
            super(vals);
        }
        public ConcretePropagatorBuilder(Sensor<?>... vals) {
            super(vals);
        }
        public ConcretePropagatorBuilder(boolean propagatingAll, Iterable<? extends Sensor<?>> butVals) {
            super(propagatingAll, butVals);
        }
    }

    private static class ConcreteCombinorBuilder<S, T> extends CombinorBuilder<S, T, ConcreteCombinorBuilder<S, T>> {
        public ConcreteCombinorBuilder(AttributeSensor<? extends S>... vals) {
            super(vals);
        }
        public ConcreteCombinorBuilder(Collection<AttributeSensor<? extends S>> vals) {
            super(vals);
        }
    }

    private static class ConcreteTransformerBuilder<S, T> extends TransformerBuilder<S, T, ConcreteTransformerBuilder<S, T>> {
        public ConcreteTransformerBuilder(AttributeSensor<S> val) {
            super(val);
        }
    }

    protected static <T extends Number> T average(Collection<T> vals, Number defaultValueForUnreportedSensors, Number valueToReportIfNoSensors, TypeToken<T> type) {
        Double doubleValueToReportIfNoSensors = (valueToReportIfNoSensors == null) ? null : valueToReportIfNoSensors.doubleValue();
        int count = count(vals, defaultValueForUnreportedSensors!=null);
        Double result = (count==0) ? doubleValueToReportIfNoSensors : 
            (Double) ((sum(vals, defaultValueForUnreportedSensors, 0, TypeToken.of(Double.class)) / count));
        
        return cast(result, type);
    }
    
    @SuppressWarnings("unchecked")
    protected static <N extends Number> N cast(Number n, TypeToken<? extends N> numberType) {
        return (N) TypeCoercions.castPrimitive(n, numberType.getRawType());
    }

    protected static <N extends Number> N sum(Iterable<? extends Number> vals, Number valueIfNull, Number valueIfNone, TypeToken<N> type) {
        double result = 0d;
        int count = 0;
        if (vals!=null) {
            for (Number val : vals) { 
                if (val!=null) {
                    result += val.doubleValue();
                    count++;
                } else if (valueIfNull!=null) {
                    result += valueIfNull.doubleValue();
                    count++;
                }
            }
        }
        if (count==0) return cast(valueIfNone, type);
        return cast(result, type);
    }
    
    protected static int count(Iterable<? extends Object> vals, boolean includeNullValues) {
        int result = 0;
        if (vals != null) 
            for (Object val : vals) 
                if (val!=null || includeNullValues) result++;
        return result;
    }
    
    private static <T> Map<T,T> newIdentityMap(Set<T> keys) {
        Map<T,T> result = Maps.newLinkedHashMap();
        for (T key : keys) {
            result.put(key, key);
        }
        return result;
    }
}
