# EVL architecture documentation

FoodStack's architecture is documented with [EVL](https://github.com/ilyaslabs/evl-lib): `@EVL.*`
annotations on the code plus the `*.evl.yaml` files in this folder, merged into one graph. The
manifest is [`evl.yaml`](../../evl.yaml) at the repository root.

So far two modules are documented: **media** and **settings** (settings uses media for the branding
logo). The other modules come later.

| File | Holds |
|---|---|
| `foodstack.evl.yaml` | Shared elements: actors, MongoDB, Kafka, Redis, the dead-letter topics |
| `media.evl.yaml` | Media module, its external bucket storage, errors, upload and claim flows, decisions |
| `settings.evl.yaml` | Settings module, its dependency on media, errors, branding and dead-letter flows |

Facts about one class or method (components, endpoints, data models, events, config properties)
are annotations in `modules/media` and `modules/settings`. Flows, errors, decisions and anything
that spans several classes are YAML.

## Check it

From the repository root, with the EVL CLI (Java 17+):

```
evl validate
```

or with Maven, no install needed:

```
mvn -N io.github.ilyaslabs:evl-maven-plugin:1.0.0:validate
```

When a module's source folders are added to `sources:` in `evl.yaml`, list each one; patterns such
as `modules/*/src/main/java` are not supported there.
