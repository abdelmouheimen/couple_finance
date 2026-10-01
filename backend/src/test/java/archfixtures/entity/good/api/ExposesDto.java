package archfixtures.entity.good.api;

/** Allowed: api exposes a plain record. */
public interface ExposesDto {
    Dto find();

    record Dto(String name) {}
}
