/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.sponge.service.model.permissionholder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import me.lucko.luckperms.api.ChatMetaType;
import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.DataMutateResult;
import me.lucko.luckperms.api.Node;
import me.lucko.luckperms.api.Tristate;
import me.lucko.luckperms.api.context.ImmutableContextSet;
import me.lucko.luckperms.common.cacheddata.type.MetaAccumulator;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.NodeMapType;
import me.lucko.luckperms.common.model.PermissionHolder;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.node.factory.NodeFactory;
import me.lucko.luckperms.common.node.model.NodeTypes;
import me.lucko.luckperms.sponge.service.LuckPermsService;
import me.lucko.luckperms.sponge.service.ProxyFactory;
import me.lucko.luckperms.sponge.service.model.LPSubject;
import me.lucko.luckperms.sponge.service.model.LPSubjectData;
import me.lucko.luckperms.sponge.service.model.LPSubjectReference;

import org.spongepowered.api.service.permission.PermissionService;
import org.spongepowered.api.service.permission.SubjectData;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PermissionHolderSubjectData implements LPSubjectData {
    private final LuckPermsService service;

    private final NodeMapType type;
    private final PermissionHolder holder;
    private final LPSubject parentSubject;

    public PermissionHolderSubjectData(LuckPermsService service, NodeMapType type, PermissionHolder holder, LPSubject parentSubject) {
        this.type = type;
        this.service = service;
        this.holder = holder;
        this.parentSubject = parentSubject;
    }

    private Stream<? extends Node> streamNodes() {
        return this.holder.getData(this.type).immutable().values().stream();
    }

    @Override
    public SubjectData sponge() {
        return ProxyFactory.toSponge(this);
    }

    @Override
    public LPSubject getParentSubject() {
        return this.parentSubject;
    }

    @Override
    public NodeMapType getType() {
        return this.type;
    }

    @Override
    public ImmutableMap<ImmutableContextSet, ImmutableMap<String, Boolean>> getAllPermissions() {
        ImmutableMap.Builder<ImmutableContextSet, ImmutableMap<String, Boolean>> ret = ImmutableMap.builder();
        for (Map.Entry<ImmutableContextSet, ? extends Collection<? extends Node>> entry : this.holder.getData(this.type).immutable().asMap().entrySet()) {
            ImmutableMap.Builder<String, Boolean> builder = ImmutableMap.builder();
            for (Node n : entry.getValue()) {
                builder.put(n.getPermission(), n.getValue());
            }
            ret.put(entry.getKey(), builder.build());
        }
        return ret.build();
    }

    @Override
    public ImmutableMap<String, Boolean> getPermissions(ImmutableContextSet contexts) {
        ImmutableMap.Builder<String, Boolean> builder = ImmutableMap.builder();
        for (Node n : this.holder.getData(this.type).immutable().get(contexts)) {
            builder.put(n.getPermission(), n.getValue());
        }
        return builder.build();
    }

    @Override
    public CompletableFuture<Boolean> setPermission(ImmutableContextSet contexts, String permission, Tristate tristate) {
        Objects.requireNonNull(contexts, "contexts");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(tristate, "tristate");

        if (tristate == Tristate.UNDEFINED) {
            // Unset
            Node node = NodeFactory.builder(permission).withExtraContext(contexts).build();
            this.type.run(
                    () -> this.holder.unsetPermission(node),
                    () -> this.holder.unsetTransientPermission(node)
            );
            return objectSave(this.holder).thenApply(v -> true);
        }

        Node node = NodeFactory.builder(permission).setValue(tristate.asBoolean()).withExtraContext(contexts).build();
        this.type.run(
                () -> {
                    // unset the inverse, to allow false -> true, true -> false overrides.
                    this.holder.unsetPermission(node);
                    this.holder.setPermission(node);
                },
                () -> {
                    // unset the inverse, to allow false -> true, true -> false overrides.
                    this.holder.unsetTransientPermission(node);
                    this.holder.setTransientPermission(node);
                }
        );
        return objectSave(this.holder).thenApply(v -> true);
    }

    @Override
    public CompletableFuture<Boolean> clearPermissions() {
        boolean ret = this.type.supply(
                this.holder::clearNodes,
                this.holder::clearTransientNodes
        );

        if (!ret) {
            return CompletableFuture.completedFuture(false);
        }

        if (this.holder.getType().isUser()) {
            this.service.getPlugin().getUserManager().giveDefaultIfNeeded(((User) this.holder), false);
        }

        return objectSave(this.holder).thenApply(v -> true);
    }

    @Override
    public CompletableFuture<Boolean> clearPermissions(ImmutableContextSet contexts) {
        Objects.requireNonNull(contexts, "contexts");
        boolean ret = this.type.supply(
                () -> this.holder.clearNodes(contexts),
                () -> {
                    List<Node> toRemove = streamNodes()
                            .filter(n -> n.getFullContexts().equals(contexts))
                            .collect(Collectors.toList());

                    toRemove.forEach(this.holder::unsetTransientPermission);
                    return !toRemove.isEmpty();
                }
        );

        if (!ret) {
            return CompletableFuture.completedFuture(false);
        }

        if (this.holder.getType().isUser()) {
            this.service.getPlugin().getUserManager().giveDefaultIfNeeded(((User) this.holder), false);
        }

        return objectSave(this.holder).thenApply(v -> true);
    }

    @Override
    public ImmutableMap<ImmutableContextSet, ImmutableList<LPSubjectReference>> getAllParents() {
        ImmutableMap.Builder<ImmutableContextSet, ImmutableList<LPSubjectReference>> ret = ImmutableMap.builder();
        for (Map.Entry<ImmutableContextSet, ? extends Collection<? extends Node>> entry : this.holder.getData(this.type).immutable().asMap().entrySet()) {
            ImmutableList.Builder<LPSubjectReference> builder = ImmutableList.builder();
            for (Node n : entry.getValue()) {
                if (n.isGroupNode()) {
                    builder.add(this.service.getGroupSubjects().loadSubject(n.getGroupName()).join().toReference());
                }
            }
            ret.put(entry.getKey(), builder.build());
        }
        return ret.build();
    }

    @Override
    public ImmutableList<LPSubjectReference> getParents(ImmutableContextSet contexts) {
        ImmutableList.Builder<LPSubjectReference> builder = ImmutableList.builder();
        for (Node n : this.holder.getData(this.type).immutable().get(contexts)) {
            if (n.isGroupNode()) {
                builder.add(this.service.getGroupSubjects().loadSubject(n.getGroupName()).join().toReference());
            }
        }
        return builder.build();
    }

    @Override
    public CompletableFuture<Boolean> addParent(ImmutableContextSet contexts, LPSubjectReference subject) {
        Objects.requireNonNull(contexts, "contexts");
        Objects.requireNonNull(subject, "subject");

        if (!subject.getCollectionIdentifier().equals(PermissionService.SUBJECTS_GROUP)) {
            return CompletableFuture.completedFuture(false);
        }

        Node node = NodeFactory.buildGroupNode(subject.getSubjectIdentifier())
                .withExtraContext(contexts)
                .build();

        DataMutateResult result = this.type.supply(
                () -> this.holder.setPermission(node),
                () -> this.holder.setTransientPermission(node)
        );

        if (!result.asBoolean()) {
            return CompletableFuture.completedFuture(false);
        }

        return objectSave(this.holder).thenApply(v -> true);
    }

    @Override
    public CompletableFuture<Boolean> removeParent(ImmutableContextSet contexts, LPSubjectReference subject) {
        Objects.requireNonNull(contexts, "contexts");
        Objects.requireNonNull(subject, "subject");

        if (!subject.getCollectionIdentifier().equals(PermissionService.SUBJECTS_GROUP)) {
            return CompletableFuture.completedFuture(false);
        }

        Node node = NodeFactory.buildGroupNode(subject.getSubjectIdentifier())
                .withExtraContext(contexts)
                .build();

        DataMutateResult result = this.type.supply(
                () -> this.holder.unsetPermission(node),
                () -> this.holder.unsetTransientPermission(node)
        );

        if (!result.asBoolean()) {
            return CompletableFuture.completedFuture(false);
        }

        return objectSave(this.holder).thenApply(v -> true);
    }

    @Override
    public CompletableFuture<Boolean> clearParents() {
        boolean ret = this.type.supply(
                () -> this.holder.clearParents(true),
                () -> {
                    List<Node> toRemove = streamNodes()
                            .filter(Node::isGroupNode)
                            .collect(Collectors.toList());

                    toRemove.forEach(this.holder::unsetTransientPermission);
                    return !toRemove.isEmpty();
                }
        );

        if (!ret) {
            return CompletableFuture.completedFuture(false);
        }

        return objectSave(this.holder).thenApply(v -> true);
    }

    @Override
    public CompletableFuture<Boolean> clearParents(ImmutableContextSet contexts) {
        Objects.requireNonNull(contexts, "contexts");
        boolean ret = this.type.supply(
                () -> this.holder.clearParents(contexts, true),
                () -> {
                    List<Node> toRemove = streamNodes()
                            .filter(Node::isGroupNode)
                            .filter(n -> n.getFullContexts().equals(contexts))
                            .collect(Collectors.toList());

                    toRemove.forEach(this.holder::unsetTransientPermission);
                    return !toRemove.isEmpty();
                }
        );

        if (!ret) {
            return CompletableFuture.completedFuture(false);
        }

        return objectSave(this.holder).thenApply(v -> true);
    }

    @Override
    public ImmutableMap<ImmutableContextSet, ImmutableMap<String, String>> getAllOptions() {
        ImmutableMap.Builder<ImmutableContextSet, ImmutableMap<String, String>> ret = ImmutableMap.builder();
        for (Map.Entry<ImmutableContextSet, ? extends Collection<? extends Node>> entry : this.holder.getData(this.type).immutable().asMap().entrySet()) {
            ret.put(entry.getKey(), nodesToOptions(entry.getValue()));
        }
        return ret.build();
    }

    @Override
    public ImmutableMap<String, String> getOptions(ImmutableContextSet contexts) {
        return nodesToOptions(this.holder.getData(this.type).immutable().get(contexts));
    }

    private static ImmutableMap<String, String> nodesToOptions(Iterable<? extends Node> nodes) {
        Map<String, String> builder = new HashMap<>();
        int maxPrefixPriority = Integer.MIN_VALUE;
        int maxSuffixPriority = Integer.MIN_VALUE;

        for (Node n : nodes) {
            if (!n.getValue()) continue;
            if (!n.isMeta() && !n.isPrefix() && !n.isSuffix()) continue;

            if (n.isPrefix()) {
                Map.Entry<Integer, String> value = n.getPrefix();
                if (value.getKey() > maxPrefixPriority) {
                    builder.put(NodeTypes.PREFIX_KEY, value.getValue());
                    maxPrefixPriority = value.getKey();
                }
                continue;
            }

            if (n.isSuffix()) {
                Map.Entry<Integer, String> value = n.getSuffix();
                if (value.getKey() > maxSuffixPriority) {
                    builder.put(NodeTypes.SUFFIX_KEY, value.getValue());
                    maxSuffixPriority = value.getKey();
                }
                continue;
            }

            if (n.isMeta()) {
                Map.Entry<String, String> meta = n.getMeta();
                builder.put(meta.getKey(), meta.getValue());
            }
        }

        return ImmutableMap.copyOf(builder);
    }

    @Override
    public CompletableFuture<Boolean> setOption(ImmutableContextSet contexts, String key, String value) {
        Objects.requireNonNull(contexts, "contexts");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        Node node;
        if (key.equalsIgnoreCase(NodeTypes.PREFIX_KEY) || key.equalsIgnoreCase(NodeTypes.SUFFIX_KEY)) {
            // special handling.
            ChatMetaType type = ChatMetaType.valueOf(key.toUpperCase());

            // remove all prefixes/suffixes from the user
            streamNodes()
                    .filter(type::matches)
                    .filter(n -> n.getFullContexts().equals(contexts))
                    .forEach(n -> this.type.run(
                            () -> this.holder.unsetPermission(n),
                            () -> this.holder.unsetTransientPermission(n)
                    ));

            MetaAccumulator metaAccumulator = this.holder.accumulateMeta(null, Contexts.global().setContexts(contexts));
            metaAccumulator.complete();
            int priority = metaAccumulator.getChatMeta(type).keySet().stream().mapToInt(e -> e).max().orElse(0);
            priority += 10;

            node = NodeFactory.buildChatMetaNode(type, priority, value).withExtraContext(contexts).build();
        } else {
            // standard remove
            streamNodes()
                    .filter(n -> n.isMeta() && n.getMeta().getKey().equals(key))
                    .filter(n -> n.getFullContexts().equals(contexts))
                    .forEach(n -> this.type.run(
                            () -> this.holder.unsetPermission(n),
                            () -> this.holder.unsetTransientPermission(n)
                    ));

            node = NodeFactory.buildMetaNode(key, value).withExtraContext(contexts).build();
        }

        this.type.run(
                () -> this.holder.setPermission(node),
                () -> this.holder.setTransientPermission(node)
        );
        return objectSave(this.holder).thenApply(v -> true);
    }

    @Override
    public CompletableFuture<Boolean> unsetOption(ImmutableContextSet contexts, String key) {
        Objects.requireNonNull(contexts, "contexts");
        Objects.requireNonNull(key, "key");

        streamNodes()
                .filter(n -> {
                    if (key.equalsIgnoreCase(NodeTypes.PREFIX_KEY)) {
                        return n.isPrefix();
                    } else if (key.equalsIgnoreCase(NodeTypes.SUFFIX_KEY)) {
                        return n.isSuffix();
                    } else {
                        return n.isMeta() && n.getMeta().getKey().equals(key);
                    }
                })
                .filter(n -> n.getFullContexts().equals(contexts))
                .forEach(node -> this.type.run(
                        () -> this.holder.unsetPermission(node),
                        () -> this.holder.unsetTransientPermission(node)
                ));

        return objectSave(this.holder).thenApply(v -> true);
    }

    @Override
    public CompletableFuture<Boolean> clearOptions(ImmutableContextSet contexts) {
        Objects.requireNonNull(contexts, "contexts");

        List<Node> toRemove = streamNodes()
                .filter(n -> n.isMeta() || n.isPrefix() || n.isSuffix())
                .filter(n -> n.getFullContexts().equals(contexts))
                .collect(Collectors.toList());

        toRemove.forEach(node -> this.type.run(
                () -> this.holder.unsetPermission(node),
                () -> this.holder.unsetTransientPermission(node)
        ));

        if (toRemove.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        return objectSave(this.holder).thenApply(v -> true);
    }

    @Override
    public CompletableFuture<Boolean> clearOptions() {
        List<Node> toRemove = streamNodes()
                .filter(n -> n.isMeta() || n.isPrefix() || n.isSuffix())
                .collect(Collectors.toList());

        toRemove.forEach(node -> this.type.run(
                () -> this.holder.unsetPermission(node),
                () -> this.holder.unsetTransientPermission(node)
        ));

        if (toRemove.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        return objectSave(this.holder).thenApply(v -> true);
    }

    private CompletableFuture<Void> objectSave(PermissionHolder t) {
        // handle transient first
        if (this.type == NodeMapType.TRANSIENT) {
            // don't bother saving to primary storage. just refresh
            if (t.getType().isGroup()) {
                this.service.getPlugin().getGroupManager().invalidateAllGroupCaches();
                this.service.getPlugin().getUserManager().invalidateAllUserCaches();
                return CompletableFuture.completedFuture(null);
            }
        }

        // handle enduring
        if (t.getType().isUser()) {
            User user = ((User) t);
            return this.service.getPlugin().getStorage().saveUser(user);
        } else {
            Group group = ((Group) t);
            this.service.getPlugin().getGroupManager().invalidateAllGroupCaches();
            this.service.getPlugin().getUserManager().invalidateAllUserCaches();
            return this.service.getPlugin().getStorage().saveGroup(group);
        }
    }
}
