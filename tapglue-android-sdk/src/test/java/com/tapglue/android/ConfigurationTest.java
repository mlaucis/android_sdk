/**
 * Copyright (c) 2015-2016 Tapglue (https://www.tapglue.com/). All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.tapglue.android;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

public class ConfigurationTest {

    private static final String URL = "https://api.tapglue.com";
    private static final String TOKEN = "token";

    Configuration configuration = new Configuration(URL, TOKEN);

    @Test
    public void configurationHasCorrectDefaultURL() {
        assertThat(configuration.getBaseUrl(), equalTo(URL));
    }

    @Test
    public void configurationHasCorrectToken() {
        assertThat(configuration.getToken(), equalTo(TOKEN));
    }

    @Test
    public void loggingIsFalseByDefault() {
        assertThat(configuration.isLogging(), equalTo(false));
    }

    @Test
    public void loggingSetsToTrue() {
        configuration.setLogging(true);

        assertThat(configuration.isLogging(), equalTo(true));
    }
}
