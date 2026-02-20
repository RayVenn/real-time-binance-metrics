class TestHealthEndpoint:
    def test_health_check(self, client):
        response = client.get("/api/v1/health")
        assert response.status_code == 200

        data = response.json()
        assert data["status"] == "healthy"
        assert data["version"] == "0.1.0"

    def test_health_check_no_api_key(self, client):
        """Health endpoint should work without API key."""
        response = client.get("/api/v1/health")
        assert response.status_code == 200


class TestAuthentication:
    def test_missing_api_key(self, client, sample_portfolio_data):
        response = client.post(
            "/api/v1/optimize/max-sharpe", json=sample_portfolio_data
        )
        assert response.status_code == 401

    def test_wrong_api_key(self, client, sample_portfolio_data):
        response = client.post(
            "/api/v1/optimize/max-sharpe",
            json=sample_portfolio_data,
            headers={"X-API-Key": "wrong-key"},
        )
        assert response.status_code == 401


class TestMaxSharpeEndpoint:
    def test_successful_optimization(self, client, sample_portfolio_data, auth_header):
        response = client.post(
            "/api/v1/optimize/max-sharpe",
            json=sample_portfolio_data,
            headers=auth_header,
        )
        assert response.status_code == 200

        data = response.json()
        assert "weights" in data
        assert "expected_return" in data
        assert "volatility" in data
        assert "sharpe_ratio" in data
        assert len(data["weights"]) == 5

    def test_with_constraints(self, client, sample_portfolio_data, auth_header):
        request_data = {
            **sample_portfolio_data,
            "constraints": {"min_weight": 0.1, "max_weight": 0.4},
        }
        response = client.post(
            "/api/v1/optimize/max-sharpe", json=request_data, headers=auth_header
        )
        assert response.status_code == 200

        data = response.json()
        for weight in data["weights"].values():
            assert weight >= 0.09  # tolerance
            assert weight <= 0.41

    def test_with_transaction_costs(self, client, sample_portfolio_data, auth_header):
        request_data = {
            **sample_portfolio_data,
            "transaction_costs": {
                "proportional": 0.001,
                "current_weights": [0.2, 0.2, 0.2, 0.2, 0.2],
            },
        }
        response = client.post(
            "/api/v1/optimize/max-sharpe", json=request_data, headers=auth_header
        )
        assert response.status_code == 200

    def test_invalid_request(self, client, auth_header):
        response = client.post(
            "/api/v1/optimize/max-sharpe", json={}, headers=auth_header
        )
        assert response.status_code == 422  # validation error

    def test_infeasible_constraints(self, client, auth_header):
        """Test that infeasible constraints return 400."""
        request_data = {
            "tickers": ["A", "B"],
            "expected_returns": [0.10, 0.12],
            "covariance_matrix": [[0.04, 0.01], [0.01, 0.05]],
            "constraints": {
                "min_weight": 0.6,  # min > 0.5 is infeasible for 2 assets
                "max_weight": 1.0,
            },
        }
        response = client.post(
            "/api/v1/optimize/max-sharpe", json=request_data, headers=auth_header
        )
        assert response.status_code == 400


class TestMinVolatilityEndpoint:
    def test_successful_optimization(self, client, sample_portfolio_data, auth_header):
        response = client.post(
            "/api/v1/optimize/min-volatility",
            json=sample_portfolio_data,
            headers=auth_header,
        )
        assert response.status_code == 200

        data = response.json()
        assert "weights" in data
        assert data["volatility"] > 0

    def test_infeasible_constraints(self, client, auth_header):
        """Test that infeasible constraints return 400."""
        request_data = {
            "tickers": ["A", "B"],
            "expected_returns": [0.10, 0.12],
            "covariance_matrix": [[0.04, 0.01], [0.01, 0.05]],
            "constraints": {
                "min_weight": 0.6,  # min > 0.5 is infeasible for 2 assets
                "max_weight": 1.0,
            },
        }
        response = client.post(
            "/api/v1/optimize/min-volatility", json=request_data, headers=auth_header
        )
        assert response.status_code == 400

    def test_with_sector_constraints(self, client, auth_header):
        request_data = {
            "tickers": ["AAPL", "GOOGL", "MSFT", "JPM", "BAC"],
            "expected_returns": [0.12, 0.10, 0.11, 0.08, 0.07],
            "covariance_matrix": [
                [0.04, 0.02, 0.015, 0.005, 0.004],
                [0.02, 0.035, 0.012, 0.006, 0.005],
                [0.015, 0.012, 0.03, 0.004, 0.003],
                [0.005, 0.006, 0.004, 0.025, 0.015],
                [0.004, 0.005, 0.003, 0.015, 0.02],
            ],
            "constraints": {
                "sector_mapper": {
                    "AAPL": "Tech",
                    "GOOGL": "Tech",
                    "MSFT": "Tech",
                    "JPM": "Finance",
                    "BAC": "Finance",
                },
                "sector_upper": {"Tech": 0.60},
                "sector_lower": {"Finance": 0.20},
            },
        }
        response = client.post(
            "/api/v1/optimize/min-volatility", json=request_data, headers=auth_header
        )
        assert response.status_code == 200


class TestEfficientReturnEndpoint:
    def test_successful_optimization(self, client, sample_portfolio_data, auth_header):
        request_data = {**sample_portfolio_data, "target_return": 0.11}
        response = client.post(
            "/api/v1/optimize/efficient-return", json=request_data, headers=auth_header
        )
        assert response.status_code == 200

        data = response.json()
        assert abs(data["expected_return"] - 0.11) < 0.01

    def test_without_target_return(self, client, sample_portfolio_data, auth_header):
        response = client.post(
            "/api/v1/optimize/efficient-return",
            json=sample_portfolio_data,
            headers=auth_header,
        )
        assert response.status_code == 400

    def test_infeasible_target_return(self, client, sample_portfolio_data, auth_header):
        # Target return higher than any individual asset
        request_data = {**sample_portfolio_data, "target_return": 0.50}
        response = client.post(
            "/api/v1/optimize/efficient-return", json=request_data, headers=auth_header
        )
        assert response.status_code == 400


class TestEfficientRiskEndpoint:
    def test_successful_optimization(self, client, sample_portfolio_data, auth_header):
        request_data = {**sample_portfolio_data, "target_volatility": 0.18}
        response = client.post(
            "/api/v1/optimize/efficient-risk", json=request_data, headers=auth_header
        )
        assert response.status_code == 200

        data = response.json()
        assert abs(data["volatility"] - 0.18) < 0.02

    def test_without_target_volatility(
        self, client, sample_portfolio_data, auth_header
    ):
        response = client.post(
            "/api/v1/optimize/efficient-risk",
            json=sample_portfolio_data,
            headers=auth_header,
        )
        assert response.status_code == 400


class TestEdgeCases:
    def test_two_asset_portfolio(self, client, auth_header):
        request_data = {
            "tickers": ["A", "B"],
            "expected_returns": [0.10, 0.15],
            "covariance_matrix": [[0.04, 0.01], [0.01, 0.06]],
        }
        response = client.post(
            "/api/v1/optimize/max-sharpe", json=request_data, headers=auth_header
        )
        assert response.status_code == 200

        data = response.json()
        assert len(data["weights"]) == 2
        assert abs(sum(data["weights"].values()) - 1.0) < 0.01

    def test_single_asset_portfolio(self, client, auth_header):
        request_data = {
            "tickers": ["A"],
            "expected_returns": [0.10],
            "covariance_matrix": [[0.04]],
        }
        response = client.post(
            "/api/v1/optimize/max-sharpe", json=request_data, headers=auth_header
        )
        assert response.status_code == 200

        data = response.json()
        assert data["weights"]["A"] == 1.0

    def test_negative_expected_returns(self, client, auth_header):
        request_data = {
            "tickers": ["A", "B", "C"],
            "expected_returns": [-0.05, 0.10, 0.08],
            "covariance_matrix": [
                [0.04, 0.01, 0.005],
                [0.01, 0.05, 0.01],
                [0.005, 0.01, 0.03],
            ],
        }
        response = client.post(
            "/api/v1/optimize/max-sharpe", json=request_data, headers=auth_header
        )
        assert response.status_code == 200

        data = response.json()
        # Should allocate little or nothing to negative return asset
        assert data["weights"]["A"] < 0.1

    def test_custom_risk_free_rate(self, client, small_portfolio_data, auth_header):
        request_data = {**small_portfolio_data, "risk_free_rate": 0.05}
        response = client.post(
            "/api/v1/optimize/max-sharpe", json=request_data, headers=auth_header
        )
        assert response.status_code == 200

    def test_allowing_short_selling(self, client, auth_header):
        request_data = {
            "tickers": ["A", "B", "C"],
            "expected_returns": [0.10, 0.12, 0.08],
            "covariance_matrix": [
                [0.04, 0.01, 0.005],
                [0.01, 0.05, 0.01],
                [0.005, 0.01, 0.03],
            ],
            "constraints": {"min_weight": -0.5, "max_weight": 1.5},
        }
        response = client.post(
            "/api/v1/optimize/max-sharpe", json=request_data, headers=auth_header
        )
        assert response.status_code == 200
