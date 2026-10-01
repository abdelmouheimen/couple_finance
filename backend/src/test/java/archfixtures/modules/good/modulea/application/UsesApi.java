package archfixtures.modules.good.modulea.application;

import archfixtures.modules.good.moduleb.api.PublicApi;

/** Allowed: module a uses module b through its api package. */
public class UsesApi {
    PublicApi api;
}
