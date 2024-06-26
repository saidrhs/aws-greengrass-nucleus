/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

/**
 * Exception for handling 4xx deployment failures.
 */
public class RetryableClientErrorException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public RetryableClientErrorException(String message) {
        super(message);
    }

    public RetryableClientErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
