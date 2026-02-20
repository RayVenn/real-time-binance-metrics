import pytest

from app.models.portfolio import (
    PortfolioConstraints,
    PortfolioRequest,
    TransactionCosts,
)
from app.services.optimizer import (
    _get_weight_bounds,
    _create_efficient_frontier,
    _build_result,
    optimize_efficient_return,
    optimize_efficient_risk,
    optimize_max_sharpe,
    optimize_min_volatility,
)


class TestGetWeightBounds:
    def test_no_constraints(self):
        bounds = _get_weight_bounds(None)
        assert bounds == (0, 1)

    def test_with_min_max(self):
        constraints = PortfolioConstraints(min_weight=0.05, max_weight=0.40)
        bounds = _get_weight_bounds(constraints)
        assert bounds == (0.05, 0.40)

    def test_with_custom_weight_bounds(self):
        constraints = PortfolioConstraints(weight_bounds=[(0.1, 0.3), (0.2, 0.5)])
        bounds = _get_weight_bounds(constraints)
        assert bounds == [(0.1, 0.3), (0.2, 0.5)]

    def test_weight_bounds_takes_precedence(self):
        constraints = PortfolioConstraints(
            min_weight=0.0,
            max_weight=1.0,
            weight_bounds=[(0.1, 0.3), (0.2, 0.5)],
        )
        bounds = _get_weight_bounds(constraints)
        assert bounds == [(0.1, 0.3), (0.2, 0.5)]


class TestOptimizeMaxSharpe:
    def test_basic_optimization(self, small_portfolio_data):
        req = PortfolioRequest(**small_portfolio_data)
        result = optimize_max_sharpe(req)

        assert len(result.weights) == 3
        assert abs(sum(result.weights.values()) - 1.0) < 0.01
        assert result.expected_return > 0
        assert result.volatility > 0
        assert result.sharpe_ratio > 0

    def test_weights_sum_to_one(self, sample_portfolio_data):
        req = PortfolioRequest(**sample_portfolio_data)
        result = optimize_max_sharpe(req)

        total_weight = sum(result.weights.values())
        assert abs(total_weight - 1.0) < 0.01

    def test_with_weight_constraints(self, small_portfolio_data):
        data = {
            **small_portfolio_data,
            "constraints": {"min_weight": 0.1, "max_weight": 0.5},
        }
        req = PortfolioRequest(**data)
        result = optimize_max_sharpe(req)

        for ticker, weight in result.weights.items():
            assert weight >= 0.1 - 0.01  # small tolerance
            assert weight <= 0.5 + 0.01

    def test_with_transaction_costs(self, small_portfolio_data):
        current = [0.33, 0.34, 0.33]
        data = {
            **small_portfolio_data,
            "transaction_costs": {"proportional": 0.01, "current_weights": current},
        }
        req = PortfolioRequest(**data)
        result = optimize_max_sharpe(req)

        # With high transaction costs, weights should stay closer to current
        assert len(result.weights) == 3


class TestOptimizeMinVolatility:
    def test_basic_optimization(self, small_portfolio_data):
        req = PortfolioRequest(**small_portfolio_data)
        result = optimize_min_volatility(req)

        assert len(result.weights) == 3
        assert abs(sum(result.weights.values()) - 1.0) < 0.01
        assert result.volatility > 0

    def test_lower_volatility_than_max_sharpe(self, small_portfolio_data):
        req = PortfolioRequest(**small_portfolio_data)
        min_vol_result = optimize_min_volatility(req)
        max_sharpe_result = optimize_max_sharpe(req)

        assert min_vol_result.volatility <= max_sharpe_result.volatility

    def test_with_sector_constraints(self):
        req = PortfolioRequest(
            tickers=["AAPL", "GOOGL", "MSFT", "JPM", "BAC"],
            expected_returns=[0.12, 0.10, 0.11, 0.08, 0.07],
            covariance_matrix=[
                [0.04, 0.02, 0.015, 0.005, 0.004],
                [0.02, 0.035, 0.012, 0.006, 0.005],
                [0.015, 0.012, 0.03, 0.004, 0.003],
                [0.005, 0.006, 0.004, 0.025, 0.015],
                [0.004, 0.005, 0.003, 0.015, 0.02],
            ],
            constraints=PortfolioConstraints(
                sector_mapper={
                    "AAPL": "Tech",
                    "GOOGL": "Tech",
                    "MSFT": "Tech",
                    "JPM": "Finance",
                    "BAC": "Finance",
                },
                sector_upper={"Tech": 0.60},
                sector_lower={"Finance": 0.20},
            ),
        )
        result = optimize_min_volatility(req)

        tech_weight = sum(result.weights[t] for t in ["AAPL", "GOOGL", "MSFT"])
        finance_weight = sum(result.weights[t] for t in ["JPM", "BAC"])

        assert tech_weight <= 0.61  # small tolerance
        assert finance_weight >= 0.19

    def test_with_transaction_costs_no_current_weights(self, small_portfolio_data):
        data = {
            **small_portfolio_data,
            "transaction_costs": {"proportional": 0.001},
        }
        req = PortfolioRequest(**data)
        result = optimize_min_volatility(req)

        assert len(result.weights) == 3


class TestOptimizeEfficientReturn:
    def test_basic_optimization(self, small_portfolio_data):
        data = {**small_portfolio_data, "target_return": 0.10}
        req = PortfolioRequest(**data)
        result = optimize_efficient_return(req)

        assert len(result.weights) == 3
        assert abs(result.expected_return - 0.10) < 0.01

    def test_requires_target_return(self, small_portfolio_data):
        req = PortfolioRequest(**small_portfolio_data)
        with pytest.raises(ValueError, match="target_return is required"):
            optimize_efficient_return(req)

    def test_higher_target_means_higher_volatility(self, small_portfolio_data):
        low_target = {**small_portfolio_data, "target_return": 0.09}
        high_target = {**small_portfolio_data, "target_return": 0.11}

        low_result = optimize_efficient_return(PortfolioRequest(**low_target))
        high_result = optimize_efficient_return(PortfolioRequest(**high_target))

        assert high_result.volatility >= low_result.volatility


class TestOptimizeEfficientRisk:
    def test_basic_optimization(self, small_portfolio_data):
        data = {**small_portfolio_data, "target_volatility": 0.18}
        req = PortfolioRequest(**data)
        result = optimize_efficient_risk(req)

        assert len(result.weights) == 3
        assert abs(result.volatility - 0.18) < 0.02

    def test_requires_target_volatility(self, small_portfolio_data):
        req = PortfolioRequest(**small_portfolio_data)
        with pytest.raises(ValueError, match="target_volatility is required"):
            optimize_efficient_risk(req)

    def test_higher_volatility_means_higher_return(self, small_portfolio_data):
        low_vol = {**small_portfolio_data, "target_volatility": 0.16}
        high_vol = {**small_portfolio_data, "target_volatility": 0.20}

        low_result = optimize_efficient_risk(PortfolioRequest(**low_vol))
        high_result = optimize_efficient_risk(PortfolioRequest(**high_vol))

        assert high_result.expected_return >= low_result.expected_return


class TestCreateEfficientFrontier:
    def test_creates_frontier(self, small_portfolio_data):
        req = PortfolioRequest(**small_portfolio_data)
        ef = _create_efficient_frontier(req)
        assert ef is not None

    def test_with_all_options(self):
        req = PortfolioRequest(
            tickers=["A", "B", "C"],
            expected_returns=[0.10, 0.12, 0.08],
            covariance_matrix=[
                [0.04, 0.01, 0.005],
                [0.01, 0.05, 0.01],
                [0.005, 0.01, 0.03],
            ],
            constraints=PortfolioConstraints(
                min_weight=0.1,
                max_weight=0.6,
                sector_mapper={"A": "X", "B": "X", "C": "Y"},
                sector_upper={"X": 0.7},
            ),
            transaction_costs=TransactionCosts(
                proportional=0.001, current_weights=[0.33, 0.33, 0.34]
            ),
        )
        ef = _create_efficient_frontier(req)
        assert ef is not None


class TestBuildResult:
    def test_builds_result(self, small_portfolio_data):
        req = PortfolioRequest(**small_portfolio_data)
        ef = _create_efficient_frontier(req)
        ef.min_volatility()

        result = _build_result(ef, req.tickers, req.risk_free_rate)

        assert len(result.weights) == 3
        assert all(ticker in result.weights for ticker in req.tickers)
        assert result.expected_return > 0
        assert result.volatility > 0
